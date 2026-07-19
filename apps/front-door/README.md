# Front door (Cloudflare Worker)

The M4-A6 edge front door for the workload broker. A Cloudflare Worker bound to
**`api.workload-baseline.medusa.software`** (a wrangler custom domain — Cloudflare
manages its DNS + TLS) that proxies every request to the broker's Cloud Run
origin.

## What it does

- Forwards the request unchanged to the `run.app` origin, **preserving the
  client's `Authorization`** header (the worker/admin bearer).
- When the `INVOKER_ID_TOKEN` secret is present, attaches it as
  **`X-Serverless-Authorization`**. That lets the broker run
  `--no-allow-unauthenticated`: Google's front end admits only Worker-vetted
  traffic and rejects direct `run.app` hits for free. The two auth headers
  coexist — verified in
  [`plan/m4/findings/front-door.md`](../../../plan/m4/findings/front-door.md).

**Keyless by design.** The org disables service-account keys, so there's no key
in the Worker. `INVOKER_ID_TOKEN` is a short-lived (~1 h) Google ID token for
`front-door-invoker`, minted by the **Refresh front-door token** workflow via
impersonation and rotated on a cron with generous overlap. If it's missing or
stale the Worker just proxies without the platform header — harmless while the
broker is still public, a clean 403 once it's locked.

## Deploy

Manually, via the **Deploy front door** workflow
(`.github/workflows/deploy-front-door.yml`), which runs `wrangler deploy` with the
`CLOUDFLARE_API_TOKEN`. Additive: the new hostname works alongside the run.app URL
until the cutover re-points the SPA and the fleet.

## Secrets / vars

- `INVOKER_ID_TOKEN` (secret) — a short-lived Google ID token for
  `front-door-invoker`. **Never set by hand**; minted and pushed by the **Refresh
  front-door token** workflow (`.github/workflows/refresh-front-door-token.yml`)
  via impersonation (WIF), rotated on a cron. Keyless — no SA key exists.
- `ORIGIN_URL` / `TARGET_AUDIENCE` (vars in `wrangler.toml`) — the broker's run.app
  URL.

## Rollback

The front door is additive until the cutover. To revert after the cutover, point
the SPA `API_URL` and the fleet back at the run.app URL and re-open the broker
(`allUsers` `run.invoker`) — a few-minute change.
