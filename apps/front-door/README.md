# Front door (Cloudflare Worker)

The M4-A6 edge front door for the workload broker. A Cloudflare Worker bound to
**`api.workload-baseline.medusa.software`** (a wrangler custom domain — Cloudflare
manages its DNS + TLS) that proxies every request to the broker's Cloud Run
origin.

## What it does

- Forwards the request unchanged to the `run.app` origin, **preserving the
  client's `Authorization`** header (the worker/admin bearer).
- When the `INVOKER_SA_KEY` secret is present, mints a short-lived Google **ID
  token** for the `front-door-invoker` service account (audience = the origin,
  cached ~55 min in-isolate) and attaches it as **`X-Serverless-Authorization`**.
  That lets the broker run `--no-allow-unauthenticated`: Google's front end
  admits only Worker-vetted traffic and rejects direct `run.app` hits for free.
  The two auth headers coexist — verified in
  [`plan/m4/findings/front-door.md`](../../../plan/m4/design/00-enrollment-tokens.md).

Before the key exists (or if minting fails) it simply proxies without the
platform header — harmless while the broker is still public, a clean 403 once
it's locked.

## Deploy

Manually, via the **Deploy front door** workflow
(`.github/workflows/deploy-front-door.yml`), which runs `wrangler deploy` with the
`CLOUDFLARE_API_TOKEN`. Additive: the new hostname works alongside the run.app URL
until the cutover re-points the SPA and the fleet.

## Secrets / vars

- `INVOKER_SA_KEY` (secret) — the `front-door-invoker` SA key JSON. **Never set by
  hand**; pushed by the rotation workflow (A6.3/A7), which mints it via WIF so the
  key never touches a laptop.
- `ORIGIN_URL` / `TARGET_AUDIENCE` (vars in `wrangler.toml`) — the broker's run.app
  URL.

## Rollback

The front door is additive until the cutover. To revert after the cutover, point
the SPA `API_URL` and the fleet back at the run.app URL and re-open the broker
(`allUsers` `run.invoker`) — a few-minute change.
