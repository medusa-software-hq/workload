plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.shadow)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories { mavenCentral() }

dependencies {
  implementation(libs.clikt)
  implementation(libs.kotlinx.serialization.json)

  // Container lifecycle + log streaming for `workload run`. Brings Armeria and the Netty native
  // UDS transports (epoll on Linux) with it, so the shadow jar can talk to the daemon socket.
  implementation(project(":libraries:docker-connector"))

  // The connector declares only slf4j-api and leaves the backend to its consumer — that's us.
  // Without a binding, SLF4J prints a "No SLF4J providers were found" banner over the container's
  // own streamed output. slf4j-nop is *not* enough: Netty deliberately rejects a NOP binding and
  // falls back to java.util.logging, which then warns on every run. A real binding (configured by
  // logback.xml) is what actually keeps the framework quiet.
  runtimeOnly(libs.logback.classic)

  testImplementation(libs.kotlin.test)
}

application {
  mainClass = "software.medusa.workload.cli.MainKt"

  applicationDefaultJvmArgs =
      listOf(
          // JNA loads native libraries via System.load; recent JDKs require explicit native-access
          // opt-in.
          "--enable-native-access=ALL-UNNAMED"
      )
}

// Bake the admin OAuth client secret + API base URL into the fat jar as a resource. The Publish CLI
// workflow passes them via `-PadminOauthClientSecret` / `-PadminApiBaseUrl` (from an Actions secret
// and variable). Absent locally → empty values, and AdminConfig falls back to env vars / a default,
// so dev builds still work. Neither value is ever committed.
val adminBuildConfigDir = layout.buildDirectory.dir("generated/adminBuildConfig")

val generateAdminBuildConfig by tasks.registering {
  val clientSecret = providers.gradleProperty("adminOauthClientSecret").orElse("")
  val apiBaseUrl = providers.gradleProperty("adminApiBaseUrl").orElse("")
  inputs.property("clientSecret", clientSecret)
  inputs.property("apiBaseUrl", apiBaseUrl)
  outputs.dir(adminBuildConfigDir)
  doLast {
    val file = adminBuildConfigDir.get().file("workload-admin-build.properties").asFile
    file.parentFile.mkdirs()
    // Both values are known to be properties-safe (a `GOCSPX-…` secret and an https URL — the
    // `:` and `/` are fine in a value). Written by hand to avoid Properties.store's date comment.
    file.writeText("oauthClientSecret=${clientSecret.get()}\napiBaseUrl=${apiBaseUrl.get()}\n")
  }
}

sourceSets.named("main") { resources.srcDir(generateAdminBuildConfig) }

tasks.shadowJar {
  archiveBaseName = "workload-cli"
  archiveClassifier = ""
  archiveVersion = ""
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

// One detekt config for every module in the repo (detekt would otherwise look for a per-project
// config/detekt/detekt.yml).
detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach { useJUnitPlatform() }
