// The M4-A6 front door: a Cloudflare Worker that fronts the whole broker hostname.
//
// It proxies every request to the broker's Cloud Run (run.app) origin, preserving the client's own
// `Authorization` header (the worker or admin bearer). When the `INVOKER_ID_TOKEN` secret is present
// it attaches it as `X-Serverless-Authorization`, so the broker can run
// `--no-allow-unauthenticated`: Google's front end then admits only Worker-vetted traffic and
// rejects direct run.app hits for free (verified in plan/m4/findings/front-door.md). The two auth
// headers coexist.
//
// Keyless by design: the org disables service-account keys, so there is no key here to sign with.
// The `INVOKER_ID_TOKEN` secret holds a short-lived (~1 h) Google ID token for the front-door-invoker
// SA, minted and pushed by the refresh-front-door-token workflow via impersonation and rotated on a
// cron. If it's missing or stale the Worker just proxies without the platform header — harmless while
// the broker is still public, a clean 403 once it's locked (surfaced to the client, visible in logs).

export default {
  async fetch(request, env) {
    const inUrl = new URL(request.url);
    const target = env.ORIGIN_URL.replace(/\/+$/, "") + inUrl.pathname + inUrl.search;

    const headers = new Headers(request.headers);
    headers.delete("Host"); // fetch sets Host from the target URL; forwarding the edge Host 404s.
    if (env.INVOKER_ID_TOKEN) {
      headers.set("X-Serverless-Authorization", "Bearer " + env.INVOKER_ID_TOKEN);
    }

    const method = request.method;
    return fetch(target, {
      method,
      headers,
      body: method === "GET" || method === "HEAD" ? undefined : request.body,
      redirect: "manual",
    });
  },
};
