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

  // `workload admin --auth=service|auto` mints a Google ID token from ambient credentials (ADC on a
  // dev box, WIF in CI) for the API-URL audience, so a service account can drive the admin plane
  // with no browser. Version managed by the same BOM the backend uses.
  implementation(platform(libs.google.cloud.libraries.bom))
  implementation(libs.google.auth.oauth2.http)

  // Container lifecycle + log streaming for `workload run`. Brings Armeria and the Netty native
  // UDS transports (epoll on Linux) with it, so the shadow jar can talk to the daemon socket.
  implementation(project(":libraries:docker-connector"))

  // The container-run pipeline (claim/resolve/pull/sidecar/create-start/log/wait/teardown, plus GC
  // observe/reap) — extracted to a standalone library so it can back workloads run on a node, not
  // only via this CLI.
  implementation(project(":libraries:workload-runtime"))

  // The connector declares only slf4j-api and leaves the backend to its consumer — that's us.
  // Without a binding, SLF4J prints a "No SLF4J providers were found" banner over the container's
  // own streamed output. slf4j-nop is *not* enough: Netty deliberately rejects a NOP binding and
  // falls back to java.util.logging, which then warns on every run. A real binding (configured by
  // logback.xml) is what actually keeps the framework quiet.
  runtimeOnly(libs.logback.classic)

  testImplementation(libs.kotlin.test)

  // AdminAuthTest drives real google-auth GoogleCredentials/AccessToken types. Version managed by
  // the same BOM the backend uses.
  testImplementation(platform(libs.google.cloud.libraries.bom))
  testImplementation(libs.google.auth.oauth2.http)
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

// Bake the per-environment admin OAuth client secrets into the fat jar as a resource. The Publish
// CLI workflow passes them via `-PadminOauthClientSecret` (prod) /
// `-PstagingAdminOauthClientSecret`
// (staging) from Actions secrets. The backend URLs and OAuth client ids are NOT baked — they're
// deterministic public values carried as source constants on `Environment`. Absent locally → empty
// values, and each `Environment` falls back to its `oauthClientSecretEnvVar`, so dev builds still
// work. A staging secret that isn't wired yet simply bakes empty, and `admin login` under
// `WORKLOAD_ENVIRONMENT=staging` reports that cleanly rather than misbehaving. Nothing is
// committed.
val adminBuildConfigDir = layout.buildDirectory.dir("generated/adminBuildConfig")

val generateAdminBuildConfig by tasks.registering {
  val clientSecret = providers.gradleProperty("adminOauthClientSecret").orElse("")
  val stagingClientSecret = providers.gradleProperty("stagingAdminOauthClientSecret").orElse("")
  inputs.property("clientSecret", clientSecret)
  inputs.property("stagingClientSecret", stagingClientSecret)
  outputs.dir(adminBuildConfigDir)
  doLast {
    val file = adminBuildConfigDir.get().file("workload-admin-build.properties").asFile
    file.parentFile.mkdirs()
    // Secrets are properties-safe (`GOCSPX-…` — no `:` `=` or newline). Written by hand to avoid
    // Properties.store's date comment. An empty value is read back as absent (see BuildConfig).
    file.writeText(
        "oauthClientSecret=${clientSecret.get()}\n" +
            "stagingOauthClientSecret=${stagingClientSecret.get()}\n"
    )
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
