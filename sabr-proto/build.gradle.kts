import com.google.protobuf.gradle.*

plugins {
    `java-library`
    id("com.google.protobuf")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies { api("com.google.protobuf:protobuf-javalite:3.24.4") }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.24.4" }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}
