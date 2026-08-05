# system-test

A black-box harness that drives the **real, shipped CLI binary** (`cli/bin/workload-cli`) as a
subprocess through full `register -> exec/run` flows against a live backend, and asserts outcomes
through the admin gRPC client (`FleetService`) — the same contract the console speaks.

Two targets, one suite, selected by `SYSTEM_TEST_TARGET`:

- **`local`** (default) — a hermetic in-process backend (see `LocalStack.kt`): the exact same
  `buildServer` assembly `backend/api/impl/local`'s `main()` boots for local dev (in-memory store,
  no-op admin auth, fake token minting), just with a shortened brokered-token lifetime so the
  long-run-refresh leg is seconds, not 15 minutes. No GCP, no Docker, no network beyond loopback.
  This is what PR CI runs.
- **`staging`** — a deployed environment, reached exactly as the CLI/console do. Requires
  `STAGING_ADMIN_ID_TOKEN` (a Google-signed ID token for an allowlisted s2s admin principal — see
  `smoke/smoke.sh` for the same pattern) and, for the one leg that pulls a real container image,
  `STAGING_TEST_IMAGE_REF` + `STAGING_TEST_TARGET_SA` (from `terraform output` in
  `infra/system-test`). This is what the nightly workflow runs.

## Why `exec` instead of `run` locally

`workload worker run` pulls a pinned image through a real container registry. The backend's
`requireValidImageRef` (see `FleetServiceImpl.kt`) refuses any `docker_image` whose host isn't a
Google registry (`*.pkg.dev`/`gcr.io`) **unconditionally**, server-side — a real security boundary
(the alternative is handing a live impersonated token to an arbitrary host), not a check the local
backend's fakes bypass. A hermetic stack has no such registry to point at, so there is no honest way
to exercise `run` without a real GCP project.

Locally, the suite exercises `workload worker exec` instead everywhere the issue's coverage calls
for "running a workload" — it goes through the identical claim -> metadata-emulator -> run-report
lifecycle as `run`; only the payload (a host command vs. a container) differs. The one leg that
needs a real pull (`HappyPathSystemTest`'s `run a real container image`) self-skips locally and runs
for real against staging, using the `infra/system-test` Terraform fixtures.

## Coverage

| Leg | File |
| --- | --- |
| Enroll, approve, grant, run — happy path | `HappyPathSystemTest.kt` |
| Burnt enrollment token -> bare 404; ungranted/archived profile -> 403 | `NegativePathsSystemTest.kt` |
| Pending-approval-then-approve | `PendingApprovalSystemTest.kt` |
| Mid-run revocation -> next refresh fails, run reads `LOST` | `MidRunRevocationSystemTest.kt` |
| A job outliving the token lifetime refreshes at least once | `LongRunTokenRefreshSystemTest.kt` |

## Running

```bash
# from the repo root
./gradlew :cli:installDist        # build the real CLI binary this suite drives
./gradlew :system-test:test       # SYSTEM_TEST_TARGET defaults to 'local'

# or, from this directory
task install-cli
task test
```

Against staging:

```bash
export SYSTEM_TEST_TARGET=staging
export STAGING_ADMIN_ID_TOKEN=...       # see .github/workflows/system-test-nightly.yml
export STAGING_TEST_IMAGE_REF=...       # terraform output (infra/system-test), optional
export STAGING_TEST_TARGET_SA=...       # required alongside STAGING_TEST_IMAGE_REF
./gradlew :system-test:test
```

## Fixture hygiene

Every worker/profile/enrollment-token this suite creates is named `systest-<run>-...`. Each test
class sweeps anything under that prefix older than 2 hours — at both start and end of the run (see
`sweepStaleArtifacts` in `SystemTestSupport.kt`) — so a killed CI job or a crashed prior run never
wedges a subsequent one, and staging's fleet listing doesn't accumulate test junk between nightly
runs. Sweeping is scoped strictly to the `systest-` namespace; it never touches anything a human
created.

## Design notes

- **Admin actions go through the gRPC client, not the CLI.** Setting up fixtures (enrollment
  tokens, profiles, grants) and revoking a worker mid-run are all admin-plane operations; driving
  them through `workload admin ...` would need an interactive Google sign-in (`admin login`) that
  doesn't fit an unattended suite. Only the *worker*-plane flows (`register`/`exec`/`run`/`token`)
  go through the real CLI subprocess — that's the surface the issue asks to exercise end-to-end.
- **The refresh assertion reads the CLI's own log line**, not a server-side proxy. The CLI already
  emits a structured `event=beacon.token.refresh` line (see `MetadataEmulator.kt`'s
  `RefreshingTokenCache`) on every (re-)claim; `CliProcess` captures a running subprocess's stdout/
  stderr continuously, so `LongRunTokenRefreshSystemTest` counts that line directly rather than
  inferring refreshes from request counts.
- **This module ships no product code** — everything lives under `src/test`, and it depends on
  `:backend:api:impl:shared` purely for its generated `FleetService` gRPC stubs and its `buildServer`
  assembly, reused rather than reimplemented so the harness exercises the real server code path.
