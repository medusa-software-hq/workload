# Edge rules (Cloudflare WAF) — M5-07

Rules-as-code for the Cloudflare front door protecting the API hosts
(`api.workload-baseline[-staging].medusa.software`). Two rules:

1. **Worker-plane country allowlist** (`http_request_firewall_custom`): requests to `/worker/*` (the
   token-broker plane used by registered worker nodes) are blocked unless they geolocate to an
   allowlisted country (`PL` today). Deliberately scoped to `/worker/` — **not** `/health` or the
   admin `FleetService` plane — because those are hit by CI from cloud regions (the promotion smoke
   and s2s admin), and a country block there would lock out the deploy gate.
2. **Per-IP rate limit** (`http_ratelimit`): one rule across the whole API front door, counted per
   `(ip.src, cf.colo.id)` — 300 requests / 60s, then block for 60s. Generous enough that legitimate
   CLI/worker/registration traffic and the smoke never trip it.

**DDoS**: Cloudflare's managed L3/4 + HTTP DDoS rulesets are on by default for the zone and are not
managed here (there is no per-zone toggle to keep in code; they are always-on).

## Why this root is not per-env workspaced

Every other Terraform root here is per-GCP-project, so `staging` and `default` (prod) workspaces own
disjoint resources. Cloudflare is different: both environments share **one** zone
(`medusa.software`), and a phase's *entrypoint* ruleset is a single object per zone — two workspaces
would fight over it. So this single, un-workspaced root owns both environments' host-scoped rules,
and the **staging-first rollout is driven by `var.enforced_environments`** (default `["staging"]`;
a follow-up expands it to `["staging", "production"]` once staging is verified).

## Inputs

| Variable | Source |
|---|---|
| `cloudflare_zone_id` | repo var `CLOUDFLARE_ZONE_ID` (the `medusa.software` zone) |
| `cloudflare_api_token` | secret `CLOUDFLARE_WAF_TOKEN` — a **narrow** token scoped to *Zone → WAF/Rulesets : Edit* on `medusa.software` only (token hygiene: a sibling of the broader Worker-deploy token, not a reuse of it) |
| `enforced_environments` | in-code default; the rollout lever |

State lives in the shared `ms-tfstate-…` bucket under `…/apps/web/edge-rules` (default workspace).

## Apply

Trunk pushes touching `apps/web/infra/edge-rules/**` run **Apply edge rules**
(`.github/workflows/apply-edge-rules.yml`); `Check PR` validates it (`fmt`/`tflint`/`validate`, no
backend). A single apply job (no env matrix) — the zone is shared.

## Verifying (mostly un-automatable — hand-tests)

- **Country block**: from a non-`PL` egress (VPN) `curl https://api.workload-baseline-staging…/worker/v2/token` → blocked; or use Cloudflare's rule preview. A `PL` egress and all of `/health` + admin stay 200.
- **Rate limit**: a synthetic burst (>300 req/min from one IP) against staging starts returning 429/block; normal traffic is unaffected.
- **No regression**: the staging smoke suite stays green after enabling.
