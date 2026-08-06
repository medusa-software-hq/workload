#!/usr/bin/env bash
# Renders node/cloud-init/node.yaml.tmpl into a NoCloud seed ISO for a manually-attached
# ("driver none") static VM — the second of the template's two renderers (the first is
# infra/modules/node-template, via Terraform's templatefile()). Byte-for-byte the same template,
# byte-for-byte the same rendered cloud-init behavior; only how the identity volume gets attached
# differs between a cloud VM and one booted from this ISO. See node/README.md.
#
# This script only substitutes the three ${...} placeholders the template declares
# (workload_environment, node_name, cli_version) — the same three Terraform's templatefile() fills
# in. Everything else in the template is a literal shell variable meant to run on the node at
# boot, so it's left untouched by design (see the template's header comment).
#
# `workload node enroll` / `workload node create --driver none` do this same rendering from the
# CLI directly (so a plain jar install needs no shell tooling at all); this script is the
# from-the-repo equivalent for anyone driving it by hand.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: render-iso.sh --node-name NAME [--environment prod] [--cli-version latest] [--out DIR]

Renders node.yaml.tmpl into DIR/user-data + DIR/meta-data and, if genisoimage, mkisofs, or
xorriso is on PATH, DIR/node-seed.iso (volume id "cidata", the NoCloud datasource label cloud-init
looks for). Attach that ISO as this VM's boot/cloud-init CD-ROM.

This does NOT mint or write a node identity — pair it with render-identity-volume.sh (or
`workload node enroll`) for that.
EOF
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
template="$script_dir/node.yaml.tmpl"

workload_environment=prod
node_name=""
cli_version=latest
out_dir=""

while [ $# -gt 0 ]; do
  case "$1" in
    --node-name) node_name="$2"; shift 2 ;;
    --environment) workload_environment="$2"; shift 2 ;;
    --cli-version) cli_version="$2"; shift 2 ;;
    --out) out_dir="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "render-iso.sh: unknown argument '$1'" >&2; usage; exit 2 ;;
  esac
done

if [ -z "$node_name" ]; then
  echo "render-iso.sh: --node-name is required" >&2
  usage
  exit 2
fi

if [ -z "$out_dir" ]; then
  out_dir="./node-$node_name"
fi

mkdir -p "$out_dir"

sed \
  -e "s|\${workload_environment}|$workload_environment|g" \
  -e "s|\${node_name}|$node_name|g" \
  -e "s|\${cli_version}|$cli_version|g" \
  "$template" > "$out_dir/user-data"

cat > "$out_dir/meta-data" <<EOF
instance-id: $node_name
local-hostname: $node_name
EOF

echo "Wrote $out_dir/user-data and $out_dir/meta-data" >&2

iso_tool=""
for candidate in genisoimage mkisofs xorriso; do
  if command -v "$candidate" >/dev/null 2>&1; then
    iso_tool="$candidate"
    break
  fi
done

if [ -z "$iso_tool" ]; then
  echo "render-iso.sh: none of genisoimage/mkisofs/xorriso found on PATH — skipping ISO build." >&2
  echo "Install one, then run e.g.:" >&2
  echo "  genisoimage -output '$out_dir/node-seed.iso' -volid cidata -joliet -rock '$out_dir/user-data' '$out_dir/meta-data'" >&2
  exit 0
fi

iso_path="$out_dir/node-seed.iso"
if [ "$iso_tool" = xorriso ]; then
  xorriso -as mkisofs -output "$iso_path" -volid cidata -joliet -rock "$out_dir/user-data" "$out_dir/meta-data"
else
  "$iso_tool" -output "$iso_path" -volid cidata -joliet -rock "$out_dir/user-data" "$out_dir/meta-data"
fi

echo "Wrote $iso_path — attach it as this VM's boot/cloud-init CD-ROM." >&2
