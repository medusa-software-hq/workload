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

// The credential/metadata emulator, packaged as its own container image and run by `workload run`
// as a per-run network sidecar — see images/metadata-emulator/README.md.
include(":images:metadata-emulator")

include(":libraries:docker-connector")

// The container-run pipeline (claim -> resolve -> brokered pull -> sidecar wiring -> create/start
// -> log stream -> wait -> teardown, plus GC observe/reap) as a standalone library, importable
// with no dependency on the CLI — the foundation for running workloads on nodes, not just the
// operator's laptop via `workload run`.
include(":libraries:workload-runtime")

include(":libraries:token-format")

// The api build's own parent project (backend/api/impl/build.gradle.kts) configures these three.
include(":backend:front-door-refresher")

include(":backend:version-drift-checker")

include(":backend:api:impl:shared")

include(":backend:api:impl:local")

include(":backend:api:impl:gcp")

// Black-box system tests: drive the real, shipped CLI binary as a subprocess through full
// register -> exec/run flows against a live backend (a hermetic in-process stack locally, or a
// deployed environment via env vars), asserting outcomes through the admin gRPC client. See
// system-test/README.md.
include(":system-test")
