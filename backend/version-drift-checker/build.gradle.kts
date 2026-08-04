plugins {
  alias(libs.plugins.detekt)
  alias(libs.plugins.jib)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)

  application
}

repositories { mavenCentral() }

val javaVersion = 21
val containerImageRef = findProperty("jib.imageRef")?.toString() ?: "version-drift-checker"
val containerImageTag = findProperty("jib.imageTag")?.toString() ?: "local"

dependencies {
  implementation(project(":backend:api:impl:shared"))
  implementation(libs.google.cloud.monitoring)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.core)
}

application { mainClass = "software.medusa.workload.versiondrift.MainKt" }

jib {
  from { image = "eclipse-temurin:$javaVersion-jre-alpine" }

  to {
    image = containerImageRef
    tags = setOf(containerImageTag)
  }

  container { mainClass = "software.medusa.workload.versiondrift.MainKt" }
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging { events("passed", "skipped", "failed") }
}
