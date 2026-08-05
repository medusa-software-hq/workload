# metadata-emulator

The credential/metadata emulator, packaged as its own container image and run
by `workload run` as a **network sidecar** — one per run, sharing a fresh
per-run bridge network with exactly one workload container.

## Why a sidecar, not a host-bound process

The emulator used to run in the CLI's own process, bound to the host's
primary IP (see `libraries/workload-runtime`'s `MetadataEmulator.kt`). Two
problems with that:

- **Newer Docker host-access hardening.** On a modern daemon a container
  attached to a *custom* bridge network can't reach a host-side listener at
  all, custom `iptables`/`nftables` rules block it outright. The only
  container-to-host path that still worked was the *default* bridge routing
  to the host's primary address — which the sidecar no longer needs at all,
  since it's a container talking to another container on their own shared
  network.
- **No real per-tenant isolation.** Every profile's container reached the
  *same* host address; nothing but a peer-IP check (`isTrustedRunPeer`, a
  private-range test) stood between one tenant's container and another's
  token. Two containers on the same host are both "private-range" sources.

Packaging the emulator as a container fixes both: each run gets its own
bridge network with its own emulator attached, and Docker never routes
between two separate user-defined bridge networks. A container on tenant A's
network has no path to tenant B's sidecar — not a 403, an unreachable host.
That's the isolation `MetadataSidecarIsolationContractTest` (in
`libraries/workload-runtime`) proves against a real daemon.

## What's actually here

Almost nothing — the emulator's HTTP handling, token caching, and
broker-claiming all already live in `libraries/workload-runtime`
(`MetadataEmulator`, `RefreshingTokenCache`, `brokerTokenClaimer`, ...). This
module is a thin `main()` (`Main.kt`) that:

1. Reads its config from env (`MS_SIDECAR_*`, written by the run pipeline's
   `metadataSidecarEnv` — broker URL, worker id/secret, profile id, listen
   port).
2. Builds the same `MetadataEmulator` `workload exec` runs in-process, bound
   wide open (`0.0.0.0:port`) since the container's own network namespace,
   not an address check, is the isolation boundary now.
3. Starts it and stays up until `docker stop` sends SIGTERM.

The worker's `id.secret` credential lives only in *this* container's env,
never the workload container's — same boundary the brokered access token
itself already had.

## Build & publish

A Gradle module, `:images:metadata-emulator`. The fat jar is
architecture-neutral, so
[`publish-metadata-emulator.yml`](../../.github/workflows/publish-metadata-emulator.yml)
builds it once (`./gradlew :images:metadata-emulator:shadowJar`) and the
multi-arch (`linux/amd64,linux/arm64`) Docker build copies that one jar onto
each arch's JRE base, pushing to the primary Artifact Registry
(`${GCP_AR_REPO_ENDPOINT}/workload/metadata-emulator`). The CLI bakes that
resolved image ref in at publish time (see `cli`'s `BuildConfig`); a
`WORKLOAD_METADATA_SIDECAR_IMAGE` env var overrides it for local development.
