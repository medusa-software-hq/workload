# hello-workload

The image the manual end-to-end test runs. It exists because busybox proves
almost nothing: its default command is `sh`, which with no stdin exits 0
immediately and silently, so a green `workload run` tells you the container
*started* and nothing else.

This one reports what actually arrived:

```
==============================================
 hello-workload
==============================================
arch:  aarch64

identity: the brokered token is LIVE — Google accepted it.
  service account id: 104567890123456789012
  (no email claim — the token carries cloud-platform scope only; the pull above already
   proves it acts as the profile's target SA)
  scope: https://www.googleapis.com/auth/cloud-platform
  expires in: 3417s
  -> the same token pulled this image and can call GCP as this SA from in here.

environment (values are never printed — compare the fingerprint):
  NAME                                LEN  SHA256
  CLOUDSDK_AUTH_ACCESS_TOKEN          977  1f0c2b9a4d61
  DEMO_SECRET                          31  f7c3c22a2ca0
  GOOGLE_OAUTH_ACCESS_TOKEN           977  1f0c2b9a4d61
  MODE                                  5  4bb24efc9641

exiting with 0
```

That single run demonstrates the whole chain: the profile's plain env arrived,
a **secret was resolved** worker-side out of Secret Manager, and the brokered
token is not merely present but **live** — Google's `tokeninfo` accepts it.

The broker mints the token with `cloud-platform` scope only, and `tokeninfo`
returns an `email` claim only for tokens that *also* carry the `userinfo.email`
scope — so this reports the service account's numeric id rather than its email.
That's not a downgrade: the successful private-registry pull above already proves
the token acts as the profile's target SA, since nothing else on the host is
signed in to that registry.

## Why fingerprints instead of values

The obvious version of this prints the last few characters of a secret. Don't:
this output is the container's stdout, readable by anyone with daemon access via
`docker logs`, and that would put real key material there.

A sha256 prefix proves the value is *exactly* the one you expect while leaking
nothing — you compute the same hash locally and compare:

```console
$ printf '%s' 'the-value-you-expect' | shasum -a 256 | cut -c1-12
f7c3c22a2ca0
```

`terraform output hand_test_profile_inputs` prints the expected fingerprint for
the demo secret, so the hand test is a diff of two strings.

> A hash only fingerprints a **high-entropy** secret. Hashing `hunter2` is
> brute-forceable — this verifies real secrets, it doesn't make a weak one safe.

## Knobs

Both are read from the profile's env, since `workload run` never overrides the
image's command — the profile's image decides what runs.

| Env var | Effect |
| --- | --- |
| `HELLO_EXIT_CODE` | Exit with this code instead of 0 — makes exit-code passthrough testable by hand. |
| `HELLO_FINGERPRINT_CHARS` | Fingerprint length (default 12). |

## Publishing

[`publish-hello-workload.yml`](../../.github/workflows/publish-hello-workload.yml)
builds and pushes it on any change under this directory, or on demand:

```bash
gh workflow run 'Publish hello-workload image'
```

**Multi-arch (`linux/amd64,linux/arm64`)**, unlike the SPA image — that one only
ever runs on Cloud Run (amd64), whereas this runs wherever a worker does:
developer Macs and the arm64 Ubuntu VM. An amd64-only image would not run on
either without emulation. The published digest is a manifest list; the backend's
resolver accepts image indexes, so it pins correctly and each host resolves its
own arch.

It goes to the **integration-test** project's private repo, not the production
registry — the point is to be pulled through the brokered path, and it isn't a
product image.
