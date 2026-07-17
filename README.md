# Workload

An in-house starting point for **plug-and-play, GCP-compatible workers**: small,
self-contained jobs that need to touch Google Cloud resources without carrying any
long-lived Google credentials of their own.

## The idea

A worker should be trivial to hand out and run anywhere. It registers itself, an
admin approves it once, and from then on it can obtain real, short-lived GCP access
on demand. No service-account key files, no `gcloud` login, no cloud-specific
secrets baked into the worker.

That indirection is the core of the project: a **token broker** running in our
already-trusted backend. The flow is:

1. A worker runs `workload register`, gets a worker identity (id + secret) and a
   confirmation code, and waits.
2. An admin approves it in the console (matching the confirmation code) and grants
   it one or more **profiles** — each a target service account the worker is allowed
   to impersonate.
3. The worker claims a token for a specific profile (`workload token --profile
   <id>`); the broker verifies the grant, then uses its own GCP identity to mint a
   short-lived access token that **impersonates that profile's target service
   account**.
4. The worker uses that token to talk to Google Cloud (read a bucket, call an API,
   etc.) for the few minutes it stays valid, then it's gone.

The trust and blast radius live in one place — the broker and the IAM bindings
behind it — instead of being spread across every worker. Workers stay dumb and
disposable; the sensitive GCP permission stays server-side, scoped per profile, and
short-lived. Everything the broker does is audit-logged.

A project that wants one of its service accounts to be claimable through Workload
opts in from its own Terraform, via the shared
[`infra/modules/workload-impersonation/`](infra/modules/workload-impersonation)
module — ownership of the grant stays with whoever owns that Terraform.

## What's in the repo

The repo is both the broker/worker mechanism above and the surrounding platform
scaffolding a real service needs, so a new worker can be dropped in with batteries
included:

- **`cli/`** (`ms-workload`) — the worker, a standalone Kotlin CLI. `register`,
  `status`, `unregister`; `token` to claim and use a brokered
  token; `exec` to run any command with a profile's env vars (plain + secrets,
  resolved worker-side) and GCP credentials injected — no `gcloud auth`
  needed. This is the "plug" end of plug-and-play.
- **`backend/`** — a Kotlin/Armeria service on Cloud Run that hosts the worker
  registration/token-broker plane and the admin `FleetService` (workers, profiles,
  grants) the console talks to, plus the reference API it grew out of. Storage and
  auth are pluggable so the same core runs in production and locally.
- **`worker-mvp/`** — a deliberately throwaway, separately-managed slice of infra
  (a target service account, sample resources, and a `workload-impersonation`
  binding) that proves the broker flow end to end without touching the real
  backend.
- **`apps/web/`** — a React/Vite SPA, the human-facing front door (Workers and
  Profiles console pages), served by Caddy.
- **`proto/`** — the protobuf contract shared between frontend and backend; the
  single source of truth for the admin API, with client and server generated from
  it.
- **`infra/`** — shared platform infrastructure (Terraform), split by concern into
  separate roots with their own remote state rather than one monolith. Includes the
  `modules/workload-impersonation/` module that other projects consume to opt a
  service account in to being claimable.

## How it fits together

Production and local development swap out auth, storage, and the cloud identity
underneath, but the shape stays the same: a worker (or the SPA) talks to the backend,
and where GCP access is needed it's brokered rather than embedded.

Delivery is automated: GitHub Actions validate every change on pull requests and
apply/deploy on push to a trunk branch. The CLI is published as a GitHub release and
a Homebrew formula so a worker is genuinely one install away.

## Status

This is an internal, experimental project — it began life as a full-stack template
(hence the leftover counter service and SPA) and is being grown toward the worker
story above. Expect template scaffolding alongside the parts that matter. The
per-component `README.md` and `Taskfile.yml` files carry the operational detail; this
document is only meant to explain what the project is for.
