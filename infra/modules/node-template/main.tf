terraform {
  required_version = ">= 1.14"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5, < 8"
    }
  }
}

# Renders the *same* cloud-init template node/cloud-init/render-iso.sh renders for a manually
# attached (driver "none") static VM — see that script and node/README.md. Keeping one template
# file, filled in by two different renderers, is the whole point: a cloud VM and a hand-attached
# VM boot from byte-identical instructions, and only how the identity volume gets attached
# differs.
#
# Node identity is deliberately not part of this rendering. It travels on a separate, small,
# swappable volume built by node/cloud-init/render-identity-volume.sh (or `workload node enroll`)
# — see node/README.md — so an operator can rotate a node's credential by re-attaching that volume
# alone, without touching (or recreating) the instance this module's output boots.
locals {
  cloud_init_template_path = "${path.module}/../../../node/cloud-init/node.yaml.tmpl"
}

variable "workload_environment" {
  description = "The Workload environment this node registers against (prod/staging/local) — becomes $WORKLOAD_ENVIRONMENT in /etc/workload/node.env."
  type        = string
  default     = "prod"
}

variable "node_name" {
  description = "The node's registered worker name. Empty lets the node fall back to its own hostname at identity-sync time."
  type        = string
  default     = ""
}

variable "cli_version" {
  description = "The workload-cli release tag to install (e.g. 'v1.4.0'), or 'latest'."
  type        = string
  default     = "latest"
}

# The rendered #cloud-config, ready to hand to a google_compute_instance as user-data:
#
#   resource "google_compute_instance" "node" {
#     ...
#     metadata = {
#       user-data = module.node_template.user_data
#     }
#     attached_disk {
#       source = google_compute_disk.node_identity_volume.self_link
#       # formatted with a single WLIDENTITY-labeled filesystem holding `enrollment-token` —
#       # see node/README.md for how to build that disk's image.
#     }
#   }
#
# GCE's standard Debian/Ubuntu images ship cloud-init and read #cloud-config straight out of the
# user-data metadata key; no extra bootstrapping is needed on the instance resource itself.
output "user_data" {
  description = "The rendered cloud-init #cloud-config for a google_compute_instance's user-data metadata key."
  value = templatefile(local.cloud_init_template_path, {
    workload_environment = var.workload_environment
    node_name            = var.node_name
    cli_version          = var.cli_version
  })
}
