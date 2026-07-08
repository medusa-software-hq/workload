import com.google.protobuf.gradle.id

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.sqldelight)
  `java-library`
}

// Proto sources live at the repo root, shared across services.
sourceSets { main { proto { srcDir("../../../../proto") } } }

dependencies {
  api(platform(libs.armeria.bom))
  api(platform(libs.grpc.bom))
  api(platform(libs.google.cloud.libraries.bom))

  api(libs.armeria.grpc)
  api(libs.armeria.grpc.kotlin)
  api(libs.armeria.kotlin)
  api(libs.google.cloud.iamcredentials)
  api(libs.google.cloud.secretmanager)
  api(libs.grpc.kotlin.stub)
  api(libs.grpc.protobuf)
  api(libs.grpc.stub)
  api(libs.hikaricp)
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.serialization.json)
  api(libs.nimbus.jose.jwt)
  api(libs.protobuf.kotlin)
  api(libs.slf4j.api)
  implementation(libs.flyway.core)
  implementation(libs.sqldelight.jdbc.driver)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.logback.classic)
  runtimeOnly(libs.postgresql)

  testImplementation(libs.kotlin.test)
}

val grpcJavaId = "grpc"
val grpcKotlinId = "grpckt"

protobuf {
  protoc { artifact = "${libs.protobuf.protoc.get()}" }

  plugins {
    id(grpcJavaId) { artifact = "${libs.protobuf.protocGen.grpc.java.get()}" }
    id(grpcKotlinId) { artifact = "${libs.protobuf.protocGen.grpc.kotlin.get()}:jdk8@jar" }
  }

  generateProtoTasks {
    all().forEach { protoTask ->
      protoTask.plugins {
        id(grpcJavaId)
        id(grpcKotlinId)
      }
      protoTask.builtins { id("kotlin") }
    }
  }
}

sqldelight {
  databases {
    create("WorkloadDatabase") {
      packageName.set("software.medusa.workload.db")
      dialect(libs.sqldelight.postgresql.dialect)
    }
  }
}
