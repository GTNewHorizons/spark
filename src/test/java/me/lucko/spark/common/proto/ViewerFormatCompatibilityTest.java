/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.proto;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.MessageLite;
import me.lucko.spark.common.heapdump.HeapDumpSummary;
import me.lucko.spark.common.platform.SparkMetadata;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.common.sampler.SamplerBuilder;
import me.lucko.spark.common.sampler.ThreadDumper;
import me.lucko.spark.common.sampler.ThreadGrouper;
import me.lucko.spark.common.sampler.java.MergeStrategy;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.proto.SparkHeapProtos;
import me.lucko.spark.proto.SparkProtos;
import me.lucko.spark.proto.SparkSamplerProtos;
import me.lucko.spark.test.TestClass2;
import me.lucko.spark.test.plugin.TestCommandSender;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards uploads to spark.lucko.me, which decodes them with spark-viewer's schema (src/viewerProto).
 * Adding data is fine as long as the viewer can skip it and it stays small.
 */
public class ViewerFormatCompatibilityTest {

    /** Fields/values we add to viewer types must use at least this number, so later upstream additions can't clash. */
    private static final int EXTENSION_MIN_NUMBER = 1000;
    /** bytebin's default max content length (spark-usercontent.lucko.me may differ); uploads are gzipped. */
    private static final long UPLOAD_LIMIT_BYTES = 10L * 1024 * 1024;
    /** Max share of an upload that the viewer skips as unknown fields. */
    private static final double MAX_EXTENSION_SHARE = 0.10;

    /** Messages spark sends to the viewer: bytebin uploads and live (websocket) packets. */
    private static final List<String> ROOTS = Arrays.asList("SamplerData", "HeapData", "HealthData", "RawPacket", "PacketWrapper");

    /** Viewer fields we don't send yet ("Message.field", viewer names). Fields we reserve (dropped legacy ones) need no entry. */
    private static final Set<String> NOT_YET_PORTED = new HashSet<>(Arrays.asList(
            "SamplerMetadata.metrics",
            "HeapMetadata.metrics",
            "HealthMetadata.metrics",
            "HealthData.channelInfo",
            "ServerUpdateStatistics.metrics",
            "PlatformStatistics.Memory.allocBpsLast1m",
            "PlatformStatistics.Memory.allocBpsLast5m",
            "PlatformStatistics.Memory.allocBpsLast15m",
            "PlatformStatistics.Tps.gameTargetTps",
            "PlatformStatistics.Mspt.gameMaxIdealMspt",
            "PluginOrModMetadata.builtIn",
            "WorldStatistics.DataPack.builtIn"
    ));

    private static ProtoSchema viewer;
    private static ProtoSchema ours;

    @BeforeAll
    public static void loadSchemas() throws IOException {
        viewer = ProtoSchema.load("spark.test.descriptors.viewer");
        ours = ProtoSchema.load("spark.test.descriptors.main");
    }

    @Test
    public void schemaIsCompatibleWithViewer() {
        SchemaComparison comparison = new SchemaComparison();
        for (String root : ROOTS) {
            comparison.message(".spark." + root, ".spark." + root);
        }
        assertTrue(comparison.problems.isEmpty(), "Not compatible with spark.lucko.me:\n" + String.join("\n", comparison.problems));
    }

    @Test
    public void samplerUploadIsReadableByViewer(@TempDir Path directory) throws IOException {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            Sampler sampler = sampleTestThread(plugin);
            Sampler.ExportProps exportProps = new Sampler.ExportProps()
                    .creator(TestCommandSender.INSTANCE.toData())
                    .comment("test")
                    .mergeStrategy(MergeStrategy.SAME_METHOD)
                    .classSourceLookup(() -> ClassSourceLookup.create(plugin.platform()));
            SparkSamplerProtos.SamplerData data = sampler.toProto(plugin.platform(), exportProps);

            assertReadableByViewer(".spark.SamplerData", data,
                    "threads.children", "metadata.platform", "metadata.platformStatistics", "metadata.systemStatistics", "timeWindowStatistics");
        }
    }

    @Test
    public void heapUploadIsReadableByViewer(@TempDir Path directory) throws IOException {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            SparkHeapProtos.HeapData data = HeapDumpSummary.createNew().toProto(plugin.platform(), TestCommandSender.INSTANCE.toData());

            assertReadableByViewer(".spark.HeapData", data,
                    "entries", "metadata.platform", "metadata.platformStatistics", "metadata.systemStatistics");
        }
    }

    @Test
    public void healthUploadIsReadableByViewer(@TempDir Path directory) throws IOException {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            // same as HealthModule#uploadHealthReport
            SparkProtos.HealthMetadata.Builder metadata = SparkProtos.HealthMetadata.newBuilder();
            SparkMetadata.gather(plugin.platform(), TestCommandSender.INSTANCE.toData(), plugin.platform().getStartupGcStatistics()).writeTo(metadata);
            SparkProtos.HealthData data = SparkProtos.HealthData.newBuilder()
                    .setMetadata(metadata)
                    .putAllTimeWindowStatistics(sampleTestThread(plugin).exportWindowStatistics())
                    .build();

            assertReadableByViewer(".spark.HealthData", data,
                    "metadata.platform", "metadata.platformStatistics", "metadata.systemStatistics", "timeWindowStatistics");
        }
    }

    private static Sampler sampleTestThread(TestSparkPlugin plugin) {
        Thread thread = new Thread(new TestClass2(), "Test Thread");
        thread.start();
        try {
            Sampler sampler = new SamplerBuilder()
                    .threadDumper(new ThreadDumper.Specific(thread))
                    .threadGrouper(ThreadGrouper.BY_POOL)
                    .samplingInterval(10)
                    .forceJavaSampler(true)
                    .completeAfter(1, TimeUnit.SECONDS)
                    .start(plugin.platform());
            sampler.getFuture().join();
            return sampler;
        } finally {
            thread.interrupt();
        }
    }

    private static void assertReadableByViewer(String messageType, MessageLite message, String... expectedFields) throws IOException {
        byte[] bytes = message.toByteArray();
        ProtoSchema.ReadResult result = viewer.read(messageType, bytes);
        assertTrue(result.problems.isEmpty(), "spark.lucko.me can't read " + messageType + ":\n" + String.join("\n", result.problems));

        List<String> missing = Arrays.stream(expectedFields).filter(f -> !result.present.contains(f)).collect(Collectors.toList());
        assertTrue(missing.isEmpty(), messageType + ": spark.lucko.me gets no " + missing);

        assertTrue(result.unknownBytes <= bytes.length * MAX_EXTENSION_SHARE,
                messageType + ": " + result.unknownBytes + " of " + bytes.length + " bytes are unknown to spark.lucko.me");

        long uploadSize = gzippedSize(bytes);
        assertTrue(uploadSize <= UPLOAD_LIMIT_BYTES, messageType + ": upload is " + uploadSize + " bytes");
    }

    private static long gzippedSize(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.size();
    }

    /**
     * Walks both schemas from the roots, pairing fields by number and checking only what matters on the wire:
     * the viewer renames fields (camelCase, "creator" is "user") and nests some types differently.
     */
    private static final class SchemaComparison {
        final Set<String> problems = new LinkedHashSet<>();
        private final Set<String> visited = new HashSet<>();

        void message(String viewerType, String ourType) {
            if (!this.visited.add(viewerType + "|" + ourType)) {
                return;
            }
            DescriptorProto theirs = viewer.messages.get(viewerType);
            DescriptorProto mine = ours.messages.get(ourType);
            String name = viewerType.substring(".spark.".length());
            if (mine == null) {
                this.problems.add(name + " is missing");
                return;
            }

            for (FieldDescriptorProto theirField : theirs.getFieldList()) {
                FieldDescriptorProto myField = ProtoSchema.field(mine, theirField.getNumber());
                String field = name + "." + theirField.getName() + " (" + theirField.getNumber() + ")";
                if (myField == null) {
                    if (!ProtoSchema.isReserved(mine, theirField.getNumber()) && !NOT_YET_PORTED.contains(name + "." + theirField.getName())) {
                        this.problems.add(field + " is missing (list it in NOT_YET_PORTED if spark doesn't send it yet)");
                    }
                } else if (myField.getType() != theirField.getType()) {
                    this.problems.add(field + " is " + myField.getType() + " here, " + theirField.getType() + " in the viewer");
                } else if (isRepeated(myField) != isRepeated(theirField)) {
                    this.problems.add(field + " is repeated in only one schema");
                } else if (myField.getType() == FieldDescriptorProto.Type.TYPE_MESSAGE) {
                    message(theirField.getTypeName(), myField.getTypeName());
                } else if (myField.getType() == FieldDescriptorProto.Type.TYPE_ENUM) {
                    enumType(theirField.getTypeName(), myField.getTypeName());
                }
            }

            for (FieldDescriptorProto myField : mine.getFieldList()) {
                int number = myField.getNumber();
                if (ProtoSchema.field(theirs, number) != null) {
                    continue;
                }
                String field = name + "." + myField.getName() + " (" + number + ")";
                if (ProtoSchema.isReserved(theirs, number)) {
                    this.problems.add(field + " reuses a number the viewer reserved");
                } else if (number < EXTENSION_MIN_NUMBER) {
                    this.problems.add(field + " is new: give it number " + EXTENSION_MIN_NUMBER + " or higher");
                }
            }

            // a oneof keeps only the last field set, so the grouping must match or data gets dropped
            List<FieldDescriptorProto> shared = mine.getFieldList().stream()
                    .filter(f -> ProtoSchema.field(theirs, f.getNumber()) != null)
                    .collect(Collectors.toList());
            for (int i = 0; i < shared.size(); i++) {
                for (int j = i + 1; j < shared.size(); j++) {
                    FieldDescriptorProto a = shared.get(i);
                    FieldDescriptorProto b = shared.get(j);
                    boolean mineTogether = sameOneof(a, b);
                    boolean theirsTogether = sameOneof(ProtoSchema.field(theirs, a.getNumber()), ProtoSchema.field(theirs, b.getNumber()));
                    if (mineTogether != theirsTogether) {
                        this.problems.add(name + "." + a.getName() + " and " + b.getName() + " share a oneof in only one schema");
                    }
                }
            }
        }

        void enumType(String viewerType, String ourType) {
            if (!this.visited.add(viewerType + "|" + ourType)) {
                return;
            }
            EnumDescriptorProto theirs = viewer.enums.get(viewerType);
            EnumDescriptorProto mine = ours.enums.get(ourType);
            String name = viewerType.substring(".spark.".length());
            for (EnumValueDescriptorProto myValue : mine.getValueList()) {
                EnumValueDescriptorProto theirValue = enumValue(theirs, myValue.getNumber());
                String value = name + "." + myValue.getName() + " (" + myValue.getNumber() + ")";
                if (theirValue != null) {
                    if (!theirValue.getName().equals(myValue.getName())) {
                        this.problems.add(value + " is " + theirValue.getName() + " in the viewer");
                    }
                } else if (ProtoSchema.isReserved(theirs, myValue.getNumber())) {
                    this.problems.add(value + " reuses a number the viewer reserved");
                } else if (myValue.getNumber() < EXTENSION_MIN_NUMBER) {
                    this.problems.add(value + " is new: give it number " + EXTENSION_MIN_NUMBER + " or higher");
                }
            }
        }

        private static boolean sameOneof(FieldDescriptorProto a, FieldDescriptorProto b) {
            return a.hasOneofIndex() && b.hasOneofIndex() && a.getOneofIndex() == b.getOneofIndex();
        }
    }

    private static boolean isRepeated(FieldDescriptorProto field) {
        return field.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED;
    }

    private static EnumValueDescriptorProto enumValue(EnumDescriptorProto enumType, int number) {
        return enumType.getValueList().stream().filter(v -> v.getNumber() == number).findFirst().orElse(null);
    }
}
