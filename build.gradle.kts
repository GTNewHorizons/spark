plugins {
    id("com.gtnewhorizons.gtnhconvention")
    id("com.google.protobuf") version "0.9.5"
}

// src/upstreamProto: unmodified copy of upstream's .proto files (what spark.lucko.me reads). Refresh it from
// lucko/spark when upstream changes, never edit it by hand. ViewerFormatCompatibilityTest checks against it.
val upstreamProto: SourceSet = sourceSets.create("upstreamProto")

fun descriptorSet(sourceSet: String) = layout.buildDirectory.file("proto-descriptors/$sourceSet.desc").get().asFile

// Classes for the spark.lucko.me upload format (src/main/proto); keep the .proto files in sync with upstream.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    generateProtoTasks {
        all().configureEach {
            val name = sourceSet.name
            if (name == upstreamProto.name) {
                builtins { remove(getByName("java")) }
            } else {
                builtins { named("java") { option("lite") } }
            }
            if (name == "main" || name == upstreamProto.name) {
                generateDescriptorSet = true
                descriptorSetOptions.path = descriptorSet(name).path
            }
        }
    }
}

tasks.named<Test>("test") {
    inputs.files(tasks.named("generateProto"), tasks.named("generateUpstreamProtoProto"))
    systemProperty("spark.test.descriptors.main", descriptorSet("main").path)
    systemProperty("spark.test.descriptors.upstream", descriptorSet(upstreamProto.name).path)
}
