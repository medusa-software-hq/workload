#!/usr/bin/env bash
# Builds the swappable identity volume: a small ISO, volume id WLIDENTITY, holding one file —
# `enrollment-token` — that node.yaml.tmpl's workload-node-identity-sync service (and the udev
# rule that re-triggers it whenever this volume is re-attached) reads to (re-)register the node.
#
# Rotate a node's identity by minting a fresh enrollment token (`workload admin enrollment
# create`, or `workload node enroll --identity-only`), building a new volume with this script, and
# swapping it in on the running VM — no reboot, no recreate. See node/README.md.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: render-identity-volume.sh --token WLE_TOKEN [--out DIR]

Reads the enrollment token from --token, or from stdin if --token is omitted (avoids the token
landing in shell history / process listings). Writes DIR/identity/enrollment-token and, if
genisoimage, mkisofs, or xorriso is on PATH, DIR/identity.iso (volume id WLIDENTITY).
EOF
}

token=""
out_dir=""

while [ $# -gt 0 ]; do
  case "$1" in
    --token) token="$2"; shift 2 ;;
    --out) out_dir="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "render-identity-volume.sh: unknown argument '$1'" >&2; usage; exit 2 ;;
  esac
done

if [ -z "$token" ]; then
  token="$(cat)"
fi

if [ -z "$token" ]; then
  echo "render-identity-volume.sh: no token given (--token or stdin)" >&2
  usage
  exit 2
fi

if [ -z "$out_dir" ]; then
  out_dir="./node-identity"
fi

identity_dir="$out_dir/identity"
mkdir -p "$identity_dir"
umask 077
printf '%s' "$token" > "$identity_dir/enrollment-token"

echo "Wrote $identity_dir/enrollment-token" >&2

iso_tool=""
for candidate in genisoimage mkisofs xorriso; do
  if command -v "$candidate" >/dev/null 2>&1; then
    iso_tool="$candidate"
    break
  fi
done

if [ -z "$iso_tool" ]; then
  echo "render-identity-volume.sh: none of genisoimage/mkisofs/xorriso found on PATH — skipping ISO build." >&2
  echo "Install one, then run e.g.:" >&2
  echo "  genisoimage -output '$out_dir/identity.iso' -volid WLIDENTITY -joliet -rock '$identity_dir'" >&2
  exit 0
fi

iso_path="$out_dir/identity.iso"
if [ "$iso_tool" = xorriso ]; then
  xorriso -as mkisofs -output "$iso_path" -volid WLIDENTITY -joliet -rock "$identity_dir"
else
  "$iso_tool" -output "$iso_path" -volid WLIDENTITY -joliet -rock "$identity_dir"
fi

echo "Wrote $iso_path — attach/swap it as this VM's identity CD-ROM/disk." >&2
