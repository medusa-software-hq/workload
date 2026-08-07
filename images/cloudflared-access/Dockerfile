# The cloudflared-access sidecar: a per-run network sidecar (same pattern as
# images/metadata-emulator — see libraries/workload-runtime's CloudflaredAccessSidecar.kt) that lets
# a workload container reach a private service sitting behind Cloudflare Access, without the
# workload itself ever holding an Access credential or a route to anything but its own sidecar.
#
# The first stage only exists to lift the statically-linked `cloudflared` binary out of Cloudflare's
# own official image without inheriting its base — that base isn't guaranteed to carry a shell, and
# entrypoint.sh needs one. Alpine gives us a small, multi-arch-friendly final image with `/bin/sh` and
# CA certificates for the TLS connection cloudflared makes outbound to Cloudflare's edge.
FROM cloudflare/cloudflared:latest AS cloudflared

FROM alpine:3.20

RUN apk add --no-cache ca-certificates

COPY --from=cloudflared /usr/local/bin/cloudflared /usr/local/bin/cloudflared
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
