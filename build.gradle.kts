plugins {
    id("com.gtnewhorizons.gtnhconvention")
    id("com.google.protobuf") version "0.9.5"
}

// src/viewerProto: unmodified copy of spark-viewer's proto/spark.proto, the schema spark.lucko.me decodes uploads with.
// Pinned to lucko/spark-viewer@7c77eddc06587bbcd86a74681f6bbfa5d59c5dea; refresh it from there, never edit it by hand.
// ViewerFormatCompatibilityTest checks our schema and real payloads against it.
val viewerProto: SourceSet = sourceSets.create("viewerProto")

fun descriptorSet(sourceSet: String) = layout.buildDirectory.file("proto-descriptors/$sourceSet.desc").get().asFile

// Classes for the spark.lucko.me upload format (src/main/proto); keep the .proto files in sync with upstream.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    generateProtoTasks {
        all().configureEach {
            val name = sourceSet.name
            if (name == viewerProto.name) {
                builtins { remove(getByName("java")) }
            } else {
                builtins { named("java") { option("lite") } }
            }
            if (name == "main" || name == viewerProto.name) {
                generateDescriptorSet = true
                descriptorSetOptions.path = descriptorSet(name).path
            }
        }
    }
}

tasks.named<Test>("test") {
    inputs.files(tasks.named("generateProto"), tasks.named("generateViewerProtoProto"))
    systemProperty("spark.test.descriptors.main", descriptorSet("main").path)
    systemProperty("spark.test.descriptors.viewer", descriptorSet(viewerProto.name).path)
}
