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

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Message and enum types of a protoc descriptor set, keyed by full name (".spark.SamplerData").
 */
final class ProtoSchema {

    final Map<String, DescriptorProto> messages = new LinkedHashMap<>();
    final Map<String, EnumDescriptorProto> enums = new LinkedHashMap<>();

    static ProtoSchema load(String systemProperty) throws IOException {
        String path = System.getProperty(systemProperty);
        if (path == null) {
            throw new IllegalStateException(systemProperty + " is not set; run the tests through Gradle");
        }
        FileDescriptorSet set;
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            set = FileDescriptorSet.parseFrom(in);
        }
        ProtoSchema schema = new ProtoSchema();
        for (FileDescriptorProto file : set.getFileList()) {
            String prefix = file.getPackage().isEmpty() ? "" : "." + file.getPackage();
            file.getMessageTypeList().forEach(m -> schema.addMessage(prefix, m));
            file.getEnumTypeList().forEach(e -> schema.enums.put(prefix + "." + e.getName(), e));
        }
        return schema;
    }

    private void addMessage(String prefix, DescriptorProto message) {
        String name = prefix + "." + message.getName();
        this.messages.put(name, message);
        message.getNestedTypeList().forEach(m -> addMessage(name, m));
        message.getEnumTypeList().forEach(e -> this.enums.put(name + "." + e.getName(), e));
    }

    static FieldDescriptorProto field(DescriptorProto message, int number) {
        for (FieldDescriptorProto field : message.getFieldList()) {
            if (field.getNumber() == number) {
                return field;
            }
        }
        return null;
    }

    static boolean isReserved(DescriptorProto message, int number) {
        // end is exclusive for message ranges
        return message.getReservedRangeList().stream().anyMatch(r -> number >= r.getStart() && number < r.getEnd());
    }

    static boolean isReserved(EnumDescriptorProto enumType, int number) {
        // end is inclusive for enum ranges
        return enumType.getReservedRangeList().stream().anyMatch(r -> number >= r.getStart() && number <= r.getEnd());
    }

    /** Result of reading an encoded message with this schema, the way a viewer built from it would. */
    static final class ReadResult {
        /** Bytes of fields this schema doesn't know, which a reader skips. */
        long unknownBytes;
        /** Fields this schema knows but that were encoded with a wire type it can't decode. */
        final Set<String> problems = new LinkedHashSet<>();
        /** Paths of the known fields that were present, e.g. "metadata.platformStatistics". */
        final Set<String> present = new LinkedHashSet<>();
    }

    ReadResult read(String messageType, byte[] data) throws IOException {
        ReadResult result = new ReadResult();
        read(messageType, ByteString.copyFrom(data), "", result);
        return result;
    }

    private void read(String messageType, ByteString data, String parent, ReadResult result) throws IOException {
        DescriptorProto message = this.messages.get(messageType);
        CodedInputStream in = data.newCodedInput();
        while (true) {
            int start = in.getTotalBytesRead();
            int tag = in.readTag();
            if (tag == 0) {
                return;
            }
            FieldDescriptorProto field = field(message, WireFormat.getTagFieldNumber(tag));
            if (field == null) {
                in.skipField(tag);
                result.unknownBytes += in.getTotalBytesRead() - start;
                continue;
            }
            String path = parent.isEmpty() ? field.getName() : parent + "." + field.getName();
            if (!acceptsWireType(field, WireFormat.getTagWireType(tag))) {
                result.problems.add(path + ": wire type " + WireFormat.getTagWireType(tag) + " can't be read as " + field.getType());
                in.skipField(tag);
                continue;
            }
            result.present.add(path);
            if (field.getType() == FieldDescriptorProto.Type.TYPE_MESSAGE) {
                read(field.getTypeName(), in.readBytes(), path, result);
            } else {
                in.skipField(tag);
            }
        }
    }

    private static boolean acceptsWireType(FieldDescriptorProto field, int wireType) {
        int expected = wireType(field.getType());
        if (wireType == expected) {
            return true;
        }
        // repeated scalars may be packed
        return wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED
                && field.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED
                && expected != WireFormat.WIRETYPE_START_GROUP;
    }

    private static int wireType(FieldDescriptorProto.Type type) {
        switch (type) {
            case TYPE_DOUBLE:
            case TYPE_FIXED64:
            case TYPE_SFIXED64:
                return WireFormat.WIRETYPE_FIXED64;
            case TYPE_FLOAT:
            case TYPE_FIXED32:
            case TYPE_SFIXED32:
                return WireFormat.WIRETYPE_FIXED32;
            case TYPE_STRING:
            case TYPE_BYTES:
            case TYPE_MESSAGE:
                return WireFormat.WIRETYPE_LENGTH_DELIMITED;
            case TYPE_GROUP:
                return WireFormat.WIRETYPE_START_GROUP;
            default:
                return WireFormat.WIRETYPE_VARINT;
        }
    }
}
