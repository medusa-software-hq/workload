plugins {
  alias(libs.plugins.jib)
  alias(libs.plugins.kotlin.jvm)

  application
}

val javaVersion = 21
val containerPort = 8080
val containerImageRef = findProperty("jib.imageRef")?.toString() ?: "api"
val containerImageTag = findProperty("jib.imageTag")?.toString() ?: "local"

dependencies { implementation(project(":shared")) }

application { mainClass = "software.medusa.counter.server.MainKt" }

jib {
  from { image = "eclipse-temurin:$javaVersion-jre-alpine" }

  to {
    image = containerImageRef
    tags = setOf(containerImageTag)
  }

  container {
    ports = listOf(containerPort.toString())
    mainClass = "software.medusa.counter.server.MainKt"
  }
}
