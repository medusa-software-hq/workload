# cloudflared-access

A **network egress sidecar**, packaged as its own container image and run by
`workload run` alongside the metadata sidecar — one per run, on the same
per-run bridge network, joined by exactly one workload container.

## What it's for

Some workloads need to reach a service that only exists on a private network
fronted by [Cloudflare Access](https://developers.cloudflare.com/cloudflare-one/policies/access/) —
an internal API, a database admin endpoint, anything an operator doesn't want
sitting on the public internet. The workload container has no route to it and
shouldn't be handed an Access credential of its own: that's exactly the shape
of problem the metadata sidecar solves for GCP tokens, applied to a different
kind of reachability.

This image wraps Cloudflare's own `cloudflared` binary in `access tcp` mode:
it authenticates to Access once (optionally with a
[service token](https://developers.cloudflare.com/cloudflare-one/identity/service-tokens/)
for headless auth, no browser involved) and exposes a plain TCP listener the
workload container dials like any other host on its shared network. The
workload never sees the Access credential and never resolves the real
Cloudflare-fronted hostname itself.

## What's actually here

Almost nothing of our own — `cloudflared` does all the real work. This image
is:

1. A first stage that copies the `cloudflared` binary out of Cloudflare's
   official image (`cloudflare/cloudflared`), so the final image doesn't
   inherit that image's own base (not guaranteed to carry a shell).
2. `entrypoint.sh`, which reads this sidecar's config from env (`CFA_SIDECAR_*`,
   written by the run pipeline's `cloudflaredAccessSidecarEnv` — see
   `libraries/workload-runtime`'s `CloudflaredAccessSidecar.kt`) and execs
   `cloudflared access tcp --hostname ... --url 0.0.0.0:$port`, with
   `--service-token-id`/`--service-token-secret` added when a service token is
   configured.

## Isolation

Deliberately reuses the metadata sidecar's per-run network rather than
creating a second one: `startCloudflaredAccessSidecar` joins the
already-existing bridge network `startMetadataSidecar` created for this run,
so every container in a run — workload, metadata sidecar, cloudflared-access
sidecar — shares the one network no other run's containers can route onto.
See `MetadataSidecar.kt`'s doc for why that structural guarantee (a disjoint
network per run), not a peer-IP check, is what actually isolates one tenant's
run from another's.

## Build & publish

Not a Gradle module — there's no JVM code here, just a Dockerfile that pulls
in Cloudflare's published binary. `publish-cloudflared-access.yml` builds a
multi-arch (`linux/amd64,linux/arm64`) image and pushes it to the primary
Artifact Registry (`${GCP_AR_REPO_ENDPOINT}/workload/cloudflared-access`). The
CLI bakes that resolved image ref in at publish time (see `cli`'s
`BuildConfig`); a `WORKLOAD_CLOUDFLARED_ACCESS_SIDECAR_IMAGE` env var
overrides it for local development.

## Enabling it

Off by default — `workload run` only starts this sidecar when
`--private-service-hostname` is given:

```
workload worker run -p my-profile \
  --private-service-hostname internal-api.corp.example.com \
  --private-service-token-id <service-token-client-id> \
  --private-service-token-secret <service-token-client-secret>
```

The workload container then sees `WORKLOAD_PRIVATE_SERVICE_HOST` (the
hostname it asked for) and `WORKLOAD_PRIVATE_SERVICE_ADDR` (the sidecar's
`ip:port` to actually dial) in its environment. The service-token flags are
optional — omit them for a hostname reachable without one.
