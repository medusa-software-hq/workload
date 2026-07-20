plugins {
  // Apply the foojay-resolver plugin to allow automatic download of JDKs
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "workload"

// One Gradle root for every JVM module in the repo. Each module keeps its own build.gradle.kts and
// its own Taskfile/CI check — the single root just lets them share one wrapper, one version
// catalog, and one detekt config, and lets one module depend on another directly (e.g. the CLI on
// :libraries:docker-connector) without publishing or a composite build.
//
// Project paths mirror directories, so `gradlew` invoked from a module directory still defaults to
// that module (and its subprojects) — which is what each module's Taskfile relies on.
include(":cli")

// A tiny Kotlin app packaged as the hand-test container image (images/hello-workload) — an ordinary
// google-cloud-storage client that proves the Beacon path end to end from inside a `workload run`.
include(":images:hello-workload")

include(":libraries:docker-connector")

include(":libraries:token-format")

// The api build's own parent project (backend/api/impl/build.gradle.kts) configures these three.
include(":backend:front-door-refresher")

include(":backend:api:impl:shared")

include(":backend:api:impl:local")

include(":backend:api:impl:gcp")
