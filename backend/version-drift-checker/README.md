# version-drift-checker

Phase 0 of the **automated-rollout epic** (workload#126): a scheduled worker
version drift/staleness alert, per environment. See [`gcp-monitoring.tf`](../infra/gcp-monitoring.tf).

## Why

Nothing used to detect worker-image staleness — a prod worker once ran a
10-day-old image, silently executing old code, with no signal. This is the
smallest step that most reduces recurrence: a periodic check, not a rollout
mechanism.

## What it checks

For every non-archived, image-based profile, on each run it compares three
digests:

- **desired** — pinned in the profile's latest revision
  (`ProfileRevision.docker_image_digest`).
- **running** — self-reported by every *live* run against that profile
  (`Run.image_digest`, already collected by `POST /worker/v2/runs` — no new
  worker code).
- **latest-published** — the profile's `docker_image` tag resolved fresh
  against the registry, the same manifest lookup `ResolveImage`/
  `VerifyProfile` already do.

It writes two 0/1 gauges per profile to Cloud Monitoring on *every* run
(`custom.googleapis.com/workload/version_drift/desired_vs_running` and
`.../published_vs_desired`) — see [`DriftMetricWriter.kt`](src/main/kotlin/software/medusa/workload/versiondrift/DriftMetricWriter.kt).
It does not itself decide "this has gone on too long"; that's the Cloud
Monitoring alert policy's `duration`, so the check stays a dumb, stateless
snapshot and the alerting logic lives in one place (Terraform).

## How it runs

Triggered every 15 min by Cloud Scheduler, like
[front-door-refresher](../front-door-refresher); the job, its schedule, and
its alert policies live in Terraform
(`backend/infra/gcp-version-drift-checker.tf`,
`backend/infra/gcp-monitoring.tf`). It runs as the broker's own runtime
service account (`primary_service_sa`) so it can reuse the same impersonation
rights the API already has for resolving image digests, and the same
`DATABASE_URL` secret.

## Config (env)

| Var | Meaning |
| --- | --- |
| `DATABASE_URL` | JDBC URL for the shared fleet Postgres database. |
| `GCP_PROJECT_ID` | Project to write the custom metrics into. |
