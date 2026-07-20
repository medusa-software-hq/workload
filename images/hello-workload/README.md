# hello-workload

The image the manual end-to-end test runs. It exists because busybox proves
almost nothing: its default command is `sh`, which with no stdin exits 0
immediately and silently, so a green `workload run` tells you the container
*started* and nothing else.

This one is a **tiny, ordinary Kotlin app** (`src/main/kotlin/.../Main.kt`) that
uses `google-auth` two ways: it reads a GCS object with `google-cloud-storage`
(the **access-token** path) and mints an audience-bound OIDC **ID token** with
`IdTokenCredentials` (the **`identity`** path). The point is what it *doesn't*
contain: **no auth code, no token, no metadata calls of its own.** It resolves
credentials through Application Default Credentials, which inside a `workload run`
means the **metadata server the CLI runs** (M4 "Beacon"), and the library fetches
and refreshes tokens itself. A normal Google-library app, oblivious to the broker
and to refresh, "just works" — exactly as it would on a real GCE VM. That
obliviousness *is* the thing under test.

The ID-token step is a **deliberate mirror of how a hosted Flow worker mints its
credential** (`GoogleCredentials.getApplicationDefault() as IdTokenProvider`,
`INCLUDE_EMAIL` + `FORMAT_FULL`) — so one `workload run` regression-proves the
exact path a Flow worker takes, not just the access-token path.

A correct run looks like:

```
==============================================
 hello-workload (Kotlin + google-auth: GCS + ID token)
==============================================
arch:  aarch64

gcs: READ OK — gs://ms-workload-test-…-hello-probe/hello.txt (61 bytes)
  content fingerprint: 6b3a1c9f22de
  -> a live GCS object was read via ADC. No token in this process; the metadata
     server served and refreshed it for us — the workload never knew.

idtoken: MINTED via IdTokenCredentials — the same path a hosted Flow worker uses.
  audience: https://hello-workload.example.test
  email:    it-handtest@ms-workload-test-…​.iam.gserviceaccount.com
  issuer:   https://accounts.google.com
  token fingerprint: 3f2a1c9d0b71

env: GOOGLE_OAUTH_ACCESS_TOKEN present? false   (Beacon expects: false)
env: GCE_METADATA_HOST = 192.168.50.88:58046
env (values are never printed — compare the fingerprint):
  DEMO_SECRET                   32  f7c3c22a2ca0
  MODE                           5  4bb24efc9641

exiting with 0
```

That single run demonstrates the whole chain: the profile's plain env arrived, a
**secret was resolved** worker-side out of Secret Manager (`DEMO_SECRET`), the
access token is **not** in the container's environment (only the `GCE_METADATA_*`
pointers are), a **real GCS object was read** *and* an **audience-bound ID token
was minted** as the profile's target service account — using nothing but the
standard library and the metadata server. Both the GCS read and the ID token's
`email`/`aud` claims are the identity proof: only that SA is granted `objectViewer`
on the bucket, and the ID token names it.

> The ID-token step needs `ComputeEngineCredentials` (i.e. the metadata path), so
> it only matches inside a `workload run` container. Run it on a dev machine with
> `gcloud` set up and ADC resolves to *your* user creds instead — the run flags an
> `aud` mismatch and exits non-zero, by design.

## Config (all from the profile's env)

`workload run` never overrides the image's command, so everything is driven by
env vars.

| Env var | Effect |
| --- | --- |
| `HELLO_PROBE_GCS` | `gs://bucket/object` to read — the live-resource proof. Unset ⇒ the read is skipped. |
| `HELLO_IDTOKEN_AUDIENCE` | Audience for the ID-token proof (default `https://hello-workload.example.test`). |
| `HELLO_EXIT_CODE` | Exit with this instead of 0/1 — makes exit-code passthrough testable by hand. |
| `HELLO_FINGERPRINT_CHARS` | Fingerprint length (default 12). |

`infra/integration-test` provisions the bucket + object, grants the hand-test SA
`objectViewer`, and emits `HELLO_PROBE_GCS` (and its expected fingerprint) in
`terraform output hand_test_profile_inputs` — so the hand test is a diff of two
strings. A read failure exits non-zero, so a broken chain is a red run, not a
quietly green one.

## Why fingerprints instead of values

The output is the container's stdout, readable by anyone with daemon access via
`docker logs`. Printing a secret's (or object's) bytes would put real data there.
A sha256 prefix proves the value is *exactly* the one you expect while leaking
nothing — you compute the same hash locally and compare:

```console
$ printf '%s' 'the-value-you-expect' | shasum -a 256 | cut -c1-12
f7c3c22a2ca0
```

> A hash only fingerprints a **high-entropy** secret. Hashing `hunter2` is
> brute-forceable — this verifies real secrets, it doesn't make a weak one safe.

## Build & publish

The app is a Gradle module, `:images:hello-workload`. The fat jar is
architecture-neutral, so
[`publish-hello-workload.yml`](../../.github/workflows/publish-hello-workload.yml)
builds it once (`./gradlew :images:hello-workload:shadowJar`) and the multi-arch
Docker build copies that one jar onto each arch's JRE base. It runs on any change
under this directory, or on demand:

```bash
gh workflow run 'Publish hello-workload image'
```

**Multi-arch (`linux/amd64,linux/arm64`)**, unlike the SPA image — that one only
ever runs on Cloud Run (amd64), whereas this runs wherever a worker does:
developer Macs and the arm64 Ubuntu VM. The published digest is a manifest list;
the backend's resolver accepts image indexes, so it pins correctly and each host
resolves its own arch.

It goes to the **integration-test** project's private repo, not the production
registry — the point is to be pulled through the brokered path, and it isn't a
product image.
