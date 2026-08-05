plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.shadow)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories { mavenCentral() }

dependencies {
  // The whole app: this just wires MetadataEmulator's env-driven constructor
  // (metadataEmulatorFromEnv) and blocks. No auth code, no HTTP handling of its own — all of it
  // lives in the library, shared with `workload exec`'s in-process emulator.
  implementation(project(":libraries:workload-runtime"))

  // A real SLF4J backend so the emulator's own logging (event=beacon.token.refresh, etc.) actually
  // appears in `docker logs` instead of a "no provider" banner.
  runtimeOnly(libs.logback.classic)
}

application { mainClass = "software.medusa.workload.metadataemulator.MainKt" }

tasks.shadowJar {
  archiveBaseName = "metadata-emulator"
  archiveClassifier = ""
  archiveVersion = ""
}

// See the CLI module's own comment: the plain `jar` task collides with the shadow jar's output
// path, which Gradle 9.4 rejects as an implicit-dependency error unless moved off it.
tasks.jar { archiveClassifier = "thin" }

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

// One detekt config for every module in the repo.
detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }
