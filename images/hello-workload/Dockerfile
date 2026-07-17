# A deliberately non-trivial "hello" image: it proves the whole workload path actually worked,
# rather than just exiting 0 like busybox does.
#
# Multi-arch matters here (see the publish workflow's `platforms`): this runs on developer Macs and
# on the arm64 Ubuntu VM, not on Cloud Run — so unlike the SPA image, amd64-only would be useless.
FROM alpine:3.21

# coreutils: sha256sum. curl: asking Google who the brokered token belongs to.
RUN apk add --no-cache coreutils curl

COPY hello.sh /usr/local/bin/hello-workload
RUN chmod +x /usr/local/bin/hello-workload

# No CMD args: `workload run` never overrides the image's command — the profile's image decides
# what runs — so everything is driven by env vars instead.
ENTRYPOINT ["/usr/local/bin/hello-workload"]
