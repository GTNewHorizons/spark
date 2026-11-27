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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards uploads to spark.lucko.me (lucko/spark-viewer), which decodes them with upstream's schema
 * (src/upstreamProto). Adding data is fine as long as upstream readers can skip it and it stays small.
 */
public class ViewerFormatCompatibilityTest {

    /** Fields/values we add to upstream types must use at least this number, so later upstream additions can't clash. */
    private static final int EXTENSION_MIN_NUMBER = 1000;
    /** bytebin's default max content length (spark-usercontent.lucko.me may differ); uploads are gzipped. */
    private static final long UPLOAD_LIMIT_BYTES = 10L * 1024 * 1024;
    /** Max share of an upload that upstream readers skip as unknown fields. */
    private static final double MAX_EXTENSION_SHARE = 0.10;

    private static ProtoSchema upstream;
    private static ProtoSchema ours;

    @BeforeAll
    public static void loadSchemas() throws IOException {
        upstream = ProtoSchema.load("spark.test.descriptors.upstream");
        ours = ProtoSchema.load("spark.test.descriptors.main");
    }

    @Test
    public void schemaIsCompatibleWithUpstream() {
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, DescriptorProto> entry : upstream.messages.entrySet()) {
            String name = entry.getKey();
            DescriptorProto up = entry.getValue();
            DescriptorProto our = ours.messages.get(name);
            if (our == null) {
                problems.add(name + " was removed");
                continue;
            }
            for (FieldDescriptorProto upField : up.getFieldList()) {
                FieldDescriptorProto ourField = ProtoSchema.field(our, upField.getNumber());
                String field = name + "." + upField.getName() + " (" + upField.getNumber() + ")";
                if (ourField == null) {
                    problems.add(field + " was removed");
                } else if (!ourField.getName().equals(upField.getName())) {
                    problems.add(field + " was renamed to " + ourField.getName());
                } else if (ourField.getType() != upField.getType() || !ourField.getTypeName().equals(upField.getTypeName())) {
                    problems.add(field + " changed type to " + (ourField.getTypeName().isEmpty() ? ourField.getType() : ourField.getTypeName()));
                } else if (isRepeated(ourField) != isRepeated(upField)) {
                    problems.add(field + " changed between repeated and singular");
                }
            }
            for (FieldDescriptorProto ourField : our.getFieldList()) {
                int number = ourField.getNumber();
                if (ProtoSchema.field(up, number) != null) {
                    continue;
                }
                String field = name + "." + ourField.getName() + " (" + number + ")";
                if (ProtoSchema.isReserved(up, number)) {
                    problems.add(field + " reuses a number upstream reserved");
                } else if (number < EXTENSION_MIN_NUMBER) {
                    problems.add(field + " is new: give it number " + EXTENSION_MIN_NUMBER + " or higher");
                }
            }
        }

        for (Map.Entry<String, EnumDescriptorProto> entry : upstream.enums.entrySet()) {
            String name = entry.getKey();
            EnumDescriptorProto up = entry.getValue();
            EnumDescriptorProto our = ours.enums.get(name);
            if (our == null) {
                problems.add(name + " was removed");
                continue;
            }
            for (EnumValueDescriptorProto upValue : up.getValueList()) {
                EnumValueDescriptorProto ourValue = enumValue(our, upValue.getNumber());
                if (ourValue == null || !ourValue.getName().equals(upValue.getName())) {
                    problems.add(name + "." + upValue.getName() + " (" + upValue.getNumber() + ") was removed or renamed");
                }
            }
            for (EnumValueDescriptorProto ourValue : our.getValueList()) {
                int number = ourValue.getNumber();
                if (enumValue(up, number) != null) {
                    continue;
                }
                String value = name + "." + ourValue.getName() + " (" + number + ")";
                if (ProtoSchema.isReserved(up, number)) {
                    problems.add(value + " reuses a number upstream reserved");
                } else if (number < EXTENSION_MIN_NUMBER) {
                    problems.add(value + " is new: give it number " + EXTENSION_MIN_NUMBER + " or higher");
                }
            }
        }

        assertTrue(problems.isEmpty(), "Not compatible with spark.lucko.me:\n" + String.join("\n", problems));
    }

    @Test
    public void samplerUploadIsReadableUpstream(@TempDir Path directory) throws IOException {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            Sampler sampler = sampleTestThread(plugin);
            Sampler.ExportProps exportProps = new Sampler.ExportProps()
                    .creator(TestCommandSender.INSTANCE.toData())
                    .comment("test")
                    .mergeStrategy(MergeStrategy.SAME_METHOD)
                    .classSourceLookup(() -> ClassSourceLookup.create(plugin.platform()));
            SparkSamplerProtos.SamplerData data = sampler.toProto(plugin.platform(), exportProps);

            assertFalse(data.getThreadsList().isEmpty());
            assertReadableUpstream(".spark.SamplerData", data);
        }
    }

    @Test
    public void heapUploadIsReadableUpstream(@TempDir Path directory) throws IOException {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            SparkHeapProtos.HeapData data = HeapDumpSummary.createNew().toProto(plugin.platform(), TestCommandSender.INSTANCE.toData());

            assertFalse(data.getEntriesList().isEmpty());
            assertReadableUpstream(".spark.HeapData", data);
        }
    }

    @Test
    public void healthUploadIsReadableUpstream(@TempDir Path directory) throws IOException {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            // same as HealthModule#uploadHealthReport
            SparkProtos.HealthMetadata.Builder metadata = SparkProtos.HealthMetadata.newBuilder();
            SparkMetadata.gather(plugin.platform(), TestCommandSender.INSTANCE.toData(), plugin.platform().getStartupGcStatistics()).writeTo(metadata);
            SparkProtos.HealthData data = SparkProtos.HealthData.newBuilder()
                    .setMetadata(metadata)
                    .putAllTimeWindowStatistics(sampleTestThread(plugin).exportWindowStatistics())
                    .build();

            assertReadableUpstream(".spark.HealthData", data);
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

    private static void assertReadableUpstream(String messageType, MessageLite message) throws IOException {
        byte[] bytes = message.toByteArray();
        ProtoSchema.ReadResult result = upstream.read(messageType, bytes);
        assertTrue(result.problems.isEmpty(), "spark.lucko.me can't read " + messageType + ":\n" + String.join("\n", result.problems));

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

    private static boolean isRepeated(FieldDescriptorProto field) {
        return field.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED;
    }

    private static EnumValueDescriptorProto enumValue(EnumDescriptorProto enumType, int number) {
        return enumType.getValueList().stream().filter(v -> v.getNumber() == number).findFirst().orElse(null);
    }
}
