#!/bin/sh
# Wires this container's CFA_SIDECAR_* env (written by the run pipeline's
# cloudflaredAccessSidecarEnv — see libraries/workload-runtime/CloudflaredAccessSidecar.kt) onto the
# real `cloudflared access tcp` invocation. It listens on 0.0.0.0:$CFA_SIDECAR_PORT inside this
# container's own network namespace (the per-run bridge network shared with the workload container
# is the isolation boundary, same as the metadata sidecar) and forwards every connection through
# Cloudflare Access to the private hostname it was told to reach.
set -eu

: "${CFA_SIDECAR_HOSTNAME:?CFA_SIDECAR_HOSTNAME is required}"
port="${CFA_SIDECAR_PORT:-8080}"

set -- access tcp --hostname "$CFA_SIDECAR_HOSTNAME" --url "0.0.0.0:$port"

# A service token authenticates this sidecar to Access as a headless client rather than an
# interactive login. Optional — a hostname reachable without one needs neither var set.
if [ -n "${CFA_SIDECAR_SERVICE_TOKEN_ID:-}" ]; then
  set -- "$@" --service-token-id "$CFA_SIDECAR_SERVICE_TOKEN_ID"
fi
if [ -n "${CFA_SIDECAR_SERVICE_TOKEN_SECRET:-}" ]; then
  set -- "$@" --service-token-secret "$CFA_SIDECAR_SERVICE_TOKEN_SECRET"
fi

exec cloudflared "$@"
