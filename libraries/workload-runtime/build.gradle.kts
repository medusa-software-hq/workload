plugins {
  `java-library`

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories { mavenCentral() }

dependencies {
  // The run pipeline creates/starts/streams/waits on containers and pulls images through the
  // connector, so its types (DockerConnector, ContainerSummary, ImageSummary, ...) are part of
  // this library's own public API.
  api(project(":libraries:docker-connector"))

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.slf4j.api)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.core)

  // The Beacon refresh-observability test asserts the actual `event=beacon.token.refresh` line via
  // logback's ListAppender, so a real binding is needed at compile scope for tests (not just a
  // NOP, which the docker-connector library uses instead since it makes no such assertion).
  testImplementation(libs.logback.classic)

  // The metadata emulator's contract test drives a real Google auth client
  // (ComputeEngineCredentials pointed at the emulator via GCE_METADATA_HOST) — proving genuine
  // GCE-client compatibility, not just our own view of the wire shape. Version managed by the same
  // BOM the backend uses.
  testImplementation(platform(libs.google.cloud.libraries.bom))
  testImplementation(libs.google.auth.oauth2.http)
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

// One detekt config for every module in the repo (detekt would otherwise look for a per-project
// config/detekt/detekt.yml).
detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  // Contract tests self-skip (via Assumptions) when no reachable daemon is configured, so the
  // suite stays green on machines/CI without Docker. Surface which happened.
  testLogging { events("passed", "skipped", "failed") }
}
