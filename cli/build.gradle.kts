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

// Bake publish-time values into the fat jar as a resource: the per-environment admin OAuth client
// secrets, and the metadata-sidecar image ref `workload run` pulls (see MetadataSidecar.kt) — both
// resolved by the Publish CLI workflow (`-PadminOauthClientSecret` /
// `-PstagingAdminOauthClientSecret`
// from Actions secrets, `-PmetadataSidecarImage` from the `GCP_AR_REPO_ENDPOINT` Actions variable,
// since the underlying GCP project id has a build-time-random suffix and so can't be a source
// constant the way `Environment`'s backend URLs are). Absent locally → empty values: each
// `Environment` falls back to its `oauthClientSecretEnvVar`, and `run` requires
// `WORKLOAD_METADATA_SIDECAR_IMAGE` instead — so dev builds still work, just not silently. Nothing
// is committed.
val bakedBuildConfigDir = layout.buildDirectory.dir("generated/bakedBuildConfig")

val generateBuildConfig by tasks.registering {
  val clientSecret = providers.gradleProperty("adminOauthClientSecret").orElse("")
  val stagingClientSecret = providers.gradleProperty("stagingAdminOauthClientSecret").orElse("")
  val metadataSidecarImage = providers.gradleProperty("metadataSidecarImage").orElse("")
  inputs.property("clientSecret", clientSecret)
  inputs.property("stagingClientSecret", stagingClientSecret)
  inputs.property("metadataSidecarImage", metadataSidecarImage)
  outputs.dir(bakedBuildConfigDir)
  doLast {
    val file = bakedBuildConfigDir.get().file("workload-build.properties").asFile
    file.parentFile.mkdirs()
    // Secrets are properties-safe (`GOCSPX-…` — no `:` `=` or newline); an image ref is too.
    // Written
    // by hand to avoid Properties.store's date comment. An empty value reads back as absent (see
    // BuildConfig).
    file.writeText(
        "oauthClientSecret=${clientSecret.get()}\n" +
            "stagingOauthClientSecret=${stagingClientSecret.get()}\n" +
            "metadataSidecarImage=${metadataSidecarImage.get()}\n"
    )
  }
}

// `workload node enroll`/`node create` render node/cloud-init/node.yaml.tmpl from the classpath —
// the same file infra/modules/node-template renders via Terraform's templatefile(), so there is
// exactly one copy of the template, not one baked into the CLI and a second one drifting in infra.
val nodeTemplateResourcesDir = layout.buildDirectory.dir("generated/nodeTemplateResources")

val copyNodeTemplate by
    tasks.registering(Copy::class) {
      from(rootProject.file("node/cloud-init/node.yaml.tmpl"))
      into(nodeTemplateResourcesDir)
    }

sourceSets.named("main") {
  resources.srcDir(generateBuildConfig)
  resources.srcDir(copyNodeTemplate)
}

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
