// Every plugin used anywhere in the repo is declared here once, `apply false`, and applied by the
// modules that actually need it. Declaring the version only here puts each plugin on a single
// shared buildscript classpath — without this, sibling modules each resolve their own copy and
// Gradle warns "The Kotlin Gradle plugin was loaded multiple times in different subprojects, which
// is not supported and may break the build".
plugins {
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.jib) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.protobuf) apply false
  alias(libs.plugins.shadow) apply false
  alias(libs.plugins.sqldelight) apply false

  // Root-only by design: there is one version catalog (gradle/libs.versions.toml) for the whole
  // repo, so there is one task to update it. Run `./gradlew versionCatalogUpdate` from here.
  alias(libs.plugins.versionCatalogUpdate)
}
