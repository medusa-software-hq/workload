plugins {
  `java-library`

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
  alias(libs.plugins.versionCatalogUpdate)
}

repositories { mavenCentral() }

dependencies {
  api(platform(libs.armeria.bom))
  api(libs.armeria)
  api(libs.armeria.kotlin)
  api(libs.kotlinx.coroutines.core)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.slf4j.api)

  // Netty native UDS transport for the daemon socket. Ubuntu-first: both Linux classifiers so the
  // VM (arm64) and CI ubuntu-latest (x86_64) both have it; on macOS neither loads and Armeria
  // falls back (story 08 adds kqueue + verifies the macOS path). Whether native is strictly
  // required vs. JDK NIO domain sockets is exactly what story 01 measures — see the close-out note.
  runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
  runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.core)
  // Keep test output free of SLF4J "no provider" noise without dragging a logging backend into
  // the library's own runtime classpath (consumers pick their own).
  testRuntimeOnly(libs.slf4j.nop)
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  // Contract tests self-skip (via Assumptions) when no reachable daemon is configured, so the
  // suite stays green on machines/CI without Docker. Surface which happened.
  testLogging { events("passed", "skipped", "failed") }
}
