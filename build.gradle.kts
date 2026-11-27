
plugins {
    id("com.gtnewhorizons.gtnhconvention")
    id("com.google.protobuf") version "0.9.5"
}

// Classes for the spark.lucko.me upload format (src/main/proto); keep the .proto files in sync with upstream.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}
