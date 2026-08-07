# The real fallback node (workload#122 parts 1-4; see backend's FallbackPlacementReconciler and
# node/README.md): one small, always-on GCE VM, provisioned from the unified node template's
# Terraform ("cloud mode") renderer via infra/modules/node-template — the same template a static/
# UTM node boots, just filled in by templatefile() instead of render-iso.sh. Every fallback-eligible
# profile with no dedicated node runs here; the moment a dedicated node is granted the profile, the
# reconciler migrates it off automatically (see FallbackMigrationSystemTest for that flow proven
# end to end against the real backend).
#
# Gated behind var.enable_fallback_node (default false): this root has no apply workflow (see
# README.md — it's applied by hand), but it bundles many otherwise-inert resources into one state,
# and a GCE VM is the first billable, always-on thing this file introduces. The flag makes standing
# it up a deliberate, separate act from any other hand-apply of this root, rather than a side effect.
#
# One instance only — the reconciler already picks the oldest active worker flagged fallback_node,
# so a single always-on node is sufficient; scaling to more later is a bump of node_count. The
# fallback_node flag itself is set by hand once this VM has registered (`workload admin workers
# set-fallback-node`, or the console) — Terraform stands the machine up, not the fleet-store row it
# becomes, exactly like every other node this repo provisions (see node/README.md).
variable "enable_fallback_node" {
  description = "Stand up the real GCE fallback node (and its VPC). False by default so applying this root never silently creates a billable VM."
  type        = bool
  default     = false
}

variable "fallback_node_identity_image" {
  description = "Self-link or name of the GCE image holding the fallback node's identity volume (a WLIDENTITY-labeled disk with one enrollment-token file) — built by node/cloud-init/build-gce-identity-image.sh from a token `workload node enroll --name fallback-node --identity-only` mints. Required only when enable_fallback_node is true; there is no sane default because the token is a runtime secret."
  type        = string
  default     = ""
}

locals {
  fallback_node_zone = "${module.common.gcp_primary_location}-b"
}

# Minimal VPC for this project: nothing here needed one before — the rest of the project is
# serverless (Cloud Run). Deliberately narrow: one subnet, one region, no legacy/default network.
resource "google_compute_network" "fallback_node_vpc" {
  count = var.enable_fallback_node ? 1 : 0

  project                 = local.gcp_project_id
  name                    = "workload-fallback-node"
  auto_create_subnetworks = false

  depends_on = [google_project_service.apis["compute.googleapis.com"]]
}

resource "google_compute_subnetwork" "fallback_node_subnet" {
  count = var.enable_fallback_node ? 1 : 0

  project       = local.gcp_project_id
  name          = "fallback-node"
  network       = google_compute_network.fallback_node_vpc[0].id
  region        = module.common.gcp_primary_location
  ip_cidr_range = "10.10.0.0/24"
}

# SSH only from Google's Identity-Aware Proxy TCP-forwarding range — never from the open internet.
# iap.googleapis.com is already enabled project-wide (gcp-project.tf); this is its first consumer.
# Reach the node with: gcloud compute ssh workload-fallback-node --zone <zone> --tunnel-through-iap
resource "google_compute_firewall" "fallback_node_allow_iap_ssh" {
  count = var.enable_fallback_node ? 1 : 0

  project       = local.gcp_project_id
  name          = "allow-iap-ssh-fallback-node"
  network       = google_compute_network.fallback_node_vpc[0].id
  direction     = "INGRESS"
  source_ranges = ["35.235.240.0/20"]

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  target_tags = ["workload-fallback-node"]
}

# The node's own GCE identity (workload#122 part 4 / M7's GCE metadata-server node identity):
# distinct from the CI/CD and admin service accounts above, and allow-listed only on the worker
# plane's separate GCE_NODE_SERVICE_ACCOUNTS (backend/infra/gcp-service.tf), never on
# ADMIN_SERVICE_ACCOUNTS — see WorkerNodeIdentityService/GooglePrincipalVerifier. The node's actual
# registration still goes through the enrollment-token identity volume below (attached_disk); this
# allowlisting is defense-in-depth for the GCE-native identity path, not a replacement for it.
resource "google_service_account" "fallback_node" {
  count = var.enable_fallback_node ? 1 : 0

  project      = local.gcp_project_id
  account_id   = "workload-fallback-node"
  display_name = "Workload fallback node (GCE VM identity)"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

module "fallback_node_template" {
  count  = var.enable_fallback_node ? 1 : 0
  source = "./modules/node-template"

  workload_environment = "prod"
  node_name            = "fallback-node"
  cli_version          = "latest"
}

# The identity volume (node/README.md's swappable WLIDENTITY device), built once out of band from
# the enrollment token `workload node enroll --name fallback-node --identity-only` mints — see
# node/cloud-init/build-gce-identity-image.sh, which turns that token into a bootable GCE image
# this disk is sourced from. Swapping this attached_disk for one built from a freshly minted token
# (re-run the script, then `terraform apply`) rotates the node's identity without recreating the
# instance, the same no-reboot swap a static/UTM node gets from physically swapping its identity
# ISO.
resource "google_compute_disk" "fallback_node_identity" {
  count = var.enable_fallback_node ? 1 : 0

  project = local.gcp_project_id
  name    = "workload-fallback-node-identity"
  zone    = local.fallback_node_zone
  image   = var.fallback_node_identity_image
  type    = "pd-standard"
  size    = 1
}

# e2-micro with a 2G swapfile (see node/cloud-init/node.yaml.tmpl's runcmd) — the smallest shape
# that comfortably runs Docker + the workload CLI's JRE + one modest workload container at a time,
# which is all the fallback node is for: overflow capacity, not throughput.
resource "google_compute_instance" "fallback_node" {
  count = var.enable_fallback_node ? 1 : 0

  project      = local.gcp_project_id
  name         = "workload-fallback-node"
  machine_type = "e2-micro"
  zone         = local.fallback_node_zone
  tags         = ["workload-fallback-node"]

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-12"
      size  = 10
      type  = "pd-standard"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.fallback_node_subnet[0].id
    # No access_config block: no external IP. Reached only via IAP TCP forwarding (see the
    # firewall rule above) — a smaller attack surface than any node with a public address.
  }

  metadata = {
    user-data = module.fallback_node_template[0].user_data
  }

  service_account {
    email = google_service_account.fallback_node[0].email
    # Broad on paper (the node re-registers via the enrollment-token identity volume, not this
    # SA's own scopes), but the SA itself carries no IAM grants beyond what
    # backend/infra/gcp-service.tf's GCE_NODE_SERVICE_ACCOUNTS allowlist exercises — the real
    # boundary is that allowlist plus the worker plane's own grant model, not this scope.
    scopes = ["cloud-platform"]
  }

  attached_disk {
    source = google_compute_disk.fallback_node_identity[0].self_link
  }

  allow_stopping_for_update = true

  depends_on = [google_project_service.apis["compute.googleapis.com"]]
}

output "fallback_node_service_account_email" {
  description = "Paste into backend/infra/gcp-service.tf's gce_node_service_accounts if this environment's isn't already listed there."
  value       = var.enable_fallback_node ? google_service_account.fallback_node[0].email : null
}

output "fallback_node_ssh_command" {
  description = "Reach the fallback node without a public IP."
  value       = var.enable_fallback_node ? "gcloud compute ssh workload-fallback-node --project ${local.gcp_project_id} --zone ${local.fallback_node_zone} --tunnel-through-iap" : null
}
