plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.shadow)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories { mavenCentral() }

dependencies {
  // The blessed path: an ordinary GCS client. It resolves credentials via ADC — which, inside a
  // `workload run`, means the metadata server the CLI runs (M4 Beacon) — and refreshes them itself.
  // This app contains no auth code and never handles a token; that is the entire point.
  implementation(platform(libs.google.cloud.libraries.bom))
  implementation(libs.google.cloud.storage)
  // Used directly for the ID-token proof (mirrors how a hosted Flow worker mints its credential):
  // GoogleCredentials/IdTokenCredentials/IdTokenProvider. Transitive via storage, but declared so
  // the dependency this code actually compiles against is explicit.
  implementation(libs.google.auth.oauth2.http)

  // A real SLF4J backend so gRPC/google-cloud don't print a "no provider" banner over our output
  // (same reasoning as the CLI's logback dependency).
  runtimeOnly(libs.logback.classic)
}

application { mainClass = "software.medusa.workload.hello.MainKt" }

tasks.shadowJar {
  archiveBaseName = "hello-workload"
  archiveClassifier = ""
  archiveVersion = ""
  // google-cloud/gRPC ship META-INF/services entries that must be concatenated, not overwritten,
  // or credential/transport providers go missing at runtime.
  mergeServiceFiles()
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

// One detekt config for every module in the repo.
detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }
