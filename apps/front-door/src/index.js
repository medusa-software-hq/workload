// The M4-A6 front door: a Cloudflare Worker that fronts the whole broker hostname.
//
// It proxies every request to the broker's Cloud Run (run.app) origin. When an invoker
// service-account key is configured (the INVOKER_SA_KEY secret, provisioned by the rotation
// workflow — A6.3/A7), it mints a short-lived Google ID token for that SA (audience = the origin)
// and attaches it as `X-Serverless-Authorization`, so the broker can run
// `--no-allow-unauthenticated`: Google's front end then admits only Worker-vetted traffic and
// rejects direct run.app hits for free (verified in plan/m4/findings/front-door.md). The client's
// own `Authorization` header — the worker or admin bearer — is forwarded untouched; the two
// coexist.
//
// Before the key is provisioned (or if minting fails) the Worker simply proxies without the
// platform header. That's harmless while the broker is still public, and once it's locked such a
// request gets a clean 403 from Google's edge, surfaced to the client and visible in Worker logs.

// Best-effort in-isolate cache of the minted ID token: { token, exp }. Isolates are reused across
// requests, so this behaves like the ~55-minute cache the design calls for without any storage.
let cachedIdToken = null;

export default {
  async fetch(request, env) {
    const inUrl = new URL(request.url);
    const target = env.ORIGIN_URL.replace(/\/+$/, "") + inUrl.pathname + inUrl.search;

    const headers = new Headers(request.headers);
    headers.delete("Host"); // fetch sets Host from the target URL; forwarding the edge Host 404s.

    if (env.INVOKER_SA_KEY) {
      try {
        headers.set("X-Serverless-Authorization", "Bearer " + (await getIdToken(env)));
      } catch (err) {
        console.error("front-door: failed to mint ID token:", err && err.message);
      }
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

async function getIdToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedIdToken && cachedIdToken.exp - now > 300) {
    return cachedIdToken.token;
  }

  const key = JSON.parse(env.INVOKER_SA_KEY);
  const assertion = await signServiceAccountAssertion(key, env.TARGET_AUDIENCE, now);

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error("token endpoint returned " + response.status + ": " + (await response.text()));
  }

  const idToken = (await response.json()).id_token;
  cachedIdToken = { token: idToken, exp: decodeJwtClaims(idToken).exp };
  return idToken;
}

// Builds and RS256-signs the JWT-bearer assertion that asks Google's token endpoint for an ID
// token whose audience is `targetAudience` (the standard service-account -> ID token flow).
async function signServiceAccountAssertion(key, targetAudience, now) {
  const header = { alg: "RS256", typ: "JWT", kid: key.private_key_id };
  const claims = {
    iss: key.client_email,
    sub: key.client_email,
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
    target_audience: targetAudience,
  };
  const signingInput = base64url(JSON.stringify(header)) + "." + base64url(JSON.stringify(claims));
  const cryptoKey = await importPrivateKey(key.private_key);
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    cryptoKey,
    new TextEncoder().encode(signingInput),
  );
  return signingInput + "." + base64urlBytes(new Uint8Array(signature));
}

function importPrivateKey(pem) {
  const der = Uint8Array.from(
    atob(
      pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace(/\s+/g, ""),
    ),
    (c) => c.charCodeAt(0),
  );
  return crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

function base64url(str) {
  return base64urlBytes(new TextEncoder().encode(str));
}

function base64urlBytes(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function decodeJwtClaims(jwt) {
  return JSON.parse(atob(jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
}
