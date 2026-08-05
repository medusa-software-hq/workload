plugins {
  alias(libs.plugins.detekt)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
}

repositories { mavenCentral() }

val javaVersion = 21

dependencies {
  // Pulls in the generated FleetService gRPC/proto Kotlin stubs, Armeria's GrpcClients, and the
  // in-process server assembly (buildServer, InMemoryFleetStore, FakeTokenMinter, ...) this module
  // reuses to stand up the hermetic local stack and to talk admin gRPC to either target. Test-only:
  // this module ships no product code of its own, only the harness.
  testImplementation(project(":backend:api:impl:shared"))

  testImplementation(libs.kotlin.test)
  testRuntimeOnly(libs.logback.classic)
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging { events("passed", "skipped", "failed") }
  // The CLI subprocess and (for the local target) the in-process backend can both take real wall
  // clock time (registration polling, container/exec lifecycles, the long-run refresh leg) — this
  // is an end-to-end suite, not a unit suite, and Gradle's default per-test timeout is too eager.
  systemProperty("junit.jupiter.execution.timeout.default", "10m")

  // Pass the harness's own env-var contract through untouched. Gradle's `Test` task otherwise
  // starts from a filtered environment, which would silently break `SYSTEM_TEST_TARGET` et al.
  environment.putAll(System.getenv())
}
