#!/usr/bin/env bash
# Builds a bootable GCE disk image holding the identity volume — a WLIDENTITY-labeled ext4
# filesystem with one file, `enrollment-token` — for infra/gcp-fallback-node.tf's
# google_compute_disk.fallback_node_identity, and uploads it to Google Cloud so a
# google_compute_image can be built from it.
#
# GCE has no removable-CD-ROM concept the way UTM does (see UtmDriver.kt): the identity volume's
# swappable-block-device design (node/README.md) still applies — attach a fresh disk built by this
# script, no reboot — but "fresh disk" means "fresh image, fresh google_compute_disk" here, not a
# hot-swapped file the way it is for a UTM/static VM. Rotate a running node's identity by re-running
# this script with a freshly minted token, updating var.fallback_node_identity_image (or the
# GCS/image names below, if you keep the defaults) and `terraform apply`ing
# infra/gcp-fallback-node.tf — Terraform detaches the old disk and attaches the new one; the boot
# instance itself is never recreated.
#
# Requires: root (loopback mount), e2fsprogs (mkfs.ext4), tar, gzip, and the gcloud CLI
# authenticated against the target project. Run this on Linux — a Docker container
# (`docker run --rm --privileged -v $PWD:/work -w /work debian:12 ...`) is the easiest way to get
# loopback/root on macOS.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: build-gce-identity-image.sh --token WLE_TOKEN --project GCP_PROJECT [options]

Reads the enrollment token from --token, or from stdin if --token is omitted (avoids the token
landing in shell history / process listings). Builds a 64 MiB ext4 image labeled WLIDENTITY
holding one file, `enrollment-token`, uploads it to a GCS staging bucket, and creates a
google_compute_image from it.

Options:
  --project GCP_PROJECT     Required. The GCP project to upload the image into.
  --image-name NAME         Name for the resulting google_compute_image (default: workload-fallback-node-identity-<unix-timestamp>).
  --gcs-bucket BUCKET       Staging bucket for the raw disk tarball (default: <project>-workload-identity-images).
  --out DIR                 Directory to build the raw image in before upload (default: a mktemp dir).
  -h, --help                Print this help and exit.

On success, prints the image's self_link — paste it into
`terraform apply -var fallback_node_identity_image=<self_link>` (or TF_VAR_fallback_node_identity_image).
EOF
}

token=""
project=""
image_name=""
bucket=""
out_dir=""

while [ $# -gt 0 ]; do
  case "$1" in
    --token) token="$2"; shift 2 ;;
    --project) project="$2"; shift 2 ;;
    --image-name) image_name="$2"; shift 2 ;;
    --gcs-bucket) bucket="$2"; shift 2 ;;
    --out) out_dir="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "build-gce-identity-image.sh: unknown argument '$1'" >&2; usage; exit 2 ;;
  esac
done

if [ -z "$token" ]; then
  token="$(cat)"
fi
[ -n "$token" ] || { echo "build-gce-identity-image.sh: no token given (--token or stdin)" >&2; usage; exit 2; }
[ -n "$project" ] || { echo "build-gce-identity-image.sh: --project is required" >&2; usage; exit 2; }

command -v gcloud >/dev/null 2>&1 || { echo "build-gce-identity-image.sh: gcloud is required" >&2; exit 1; }
command -v mkfs.ext4 >/dev/null 2>&1 || { echo "build-gce-identity-image.sh: mkfs.ext4 (e2fsprogs) is required" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || { echo "build-gce-identity-image.sh: must run as root (loopback mount)" >&2; exit 1; }

image_name="${image_name:-workload-fallback-node-identity-$(date +%s)}"
bucket="${bucket:-${project}-workload-identity-images}"
out_dir="${out_dir:-$(mktemp -d)}"
mkdir -p "$out_dir"

raw_disk="$out_dir/disk.raw"
mount_point="$out_dir/mnt"
tarball="$out_dir/disk.raw.tar.gz"

echo "build-gce-identity-image.sh: building 64MiB WLIDENTITY image in $out_dir" >&2

fallocate -l 64M "$raw_disk"
loop_dev="$(losetup --find --show "$raw_disk")"
trap 'losetup -d "$loop_dev" 2>/dev/null || true' EXIT

mkfs.ext4 -L WLIDENTITY -q "$loop_dev"
mkdir -p "$mount_point"
mount "$loop_dev" "$mount_point"
umask 077
printf '%s' "$token" > "$mount_point/enrollment-token"
sync
umount "$mount_point"
losetup -d "$loop_dev"
trap - EXIT

# GCE's raw-disk import format: a sparse disk.raw, tarred (not gzip-compressed inside the tar —
# GCE decompresses the outer .tar.gz itself), named exactly disk.raw at the tar root.
( cd "$out_dir" && tar --sparse -czf "$tarball" disk.raw )

echo "build-gce-identity-image.sh: uploading to gs://$bucket/$image_name.tar.gz" >&2
gcloud storage buckets describe "gs://$bucket" --project "$project" >/dev/null 2>&1 ||
  gcloud storage buckets create "gs://$bucket" --project "$project" --uniform-bucket-level-access
gcloud storage cp "$tarball" "gs://$bucket/$image_name.tar.gz"

echo "build-gce-identity-image.sh: creating image $image_name" >&2
gcloud compute images create "$image_name" \
  --project "$project" \
  --source-uri "gs://$bucket/$image_name.tar.gz"

self_link="$(gcloud compute images describe "$image_name" --project "$project" --format='value(selfLink)')"
echo "$self_link"
