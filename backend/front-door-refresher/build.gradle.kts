plugins {
  alias(libs.plugins.jib)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)

  application
}

repositories { mavenCentral() }

val javaVersion = 21
val containerImageRef = findProperty("jib.imageRef")?.toString() ?: "front-door-refresher"
val containerImageTag = findProperty("jib.imageTag")?.toString() ?: "local"

dependencies {
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
}

application { mainClass = "software.medusa.workload.frontdoor.MainKt" }

jib {
  from { image = "eclipse-temurin:$javaVersion-jre-alpine" }

  to {
    image = containerImageRef
    tags = setOf(containerImageTag)
  }

  container { mainClass = "software.medusa.workload.frontdoor.MainKt" }
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging { events("passed", "skipped", "failed") }
}
