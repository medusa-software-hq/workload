plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.shadow)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
  alias(libs.plugins.versionCatalogUpdate)
}

repositories { mavenCentral() }

dependencies {
  implementation(libs.clikt)
  implementation(platform(libs.google.cloud.libraries.bom))
  implementation(libs.google.cloud.storage)
  implementation(libs.kotlinx.serialization.json)

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

tasks.shadowJar {
  archiveBaseName = "workload-cli"
  archiveClassifier = ""
  archiveVersion = ""
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach { useJUnitPlatform() }
