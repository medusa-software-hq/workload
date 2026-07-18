plugins {
  `java-library`

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories { mavenCentral() }

// Deliberately dependency-free: the token format is pure JDK (SecureRandom, CRC32, BigInteger,
// base62) so both the backend (Armeria/Flyway) and the CLI (Clikt) can depend on it without
// dragging any of the other's stack onto their classpath.
dependencies { testImplementation(libs.kotlin.test) }

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

// One detekt config for every module in the repo (detekt would otherwise look for a per-project
// config/detekt/detekt.yml).
detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging { events("passed", "skipped", "failed") }
}
