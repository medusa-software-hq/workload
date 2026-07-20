# front-door refresher

A tiny Kotlin **Cloud Run job** that keeps the [front door](../../apps/front-door)
Worker's credential fresh — keylessly (M4-A6).

Running **as the `front-door-invoker` service account**, it:

1. fetches that SA's own short-lived Google **ID token** (audience = the broker's
   Cloud Run URL) straight from the GCE metadata server — the same endpoint
   [Beacon](../../plan/m4/design/01-metadata-emulator.md) emulates; and
2. `PUT`s it into the Worker's `INVOKER_ID_TOKEN` secret via the Cloudflare API.

No key and no impersonation — the identity that needs the token is the one
running the job. The org disables service-account keys, so the front door holds
only this rotating ID token.

## How it runs

Triggered every ~15 min by **Cloud Scheduler**; the job, its schedule, and its
config live in Terraform (`backend/infra/gcp-front-door-refresher.tf`). The image
is built with **jib** (no Dockerfile) and pushed by the **Deploy front-door
refresher** workflow.

## Config (env)

| Var | Meaning |
| --- | --- |
| `TARGET_AUDIENCE` | The broker's Cloud Run URL — the ID token's audience. |
| `CF_ACCOUNT_ID` / `CF_WORKER_NAME` | Which Cloudflare Worker to update. |
| `CF_API_TOKEN` | A scoped (`Workers Scripts: Edit` only) token, from Secret Manager. |
| `WORKER_SECRET_NAME` | The Worker secret to write (default `INVOKER_ID_TOKEN`). |
