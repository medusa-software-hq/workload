# Configuration
#
# The Cloud Run domain mapping is split out from the web foundation because it
# must be applied by the shared org-level domain-mapper service account (the only
# identity that's a verified owner of the domain), not by this project's CI/CD SA.

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/counter/v4/apps/web/domain-mapping" # 🎨 TEMPLATE EJECT: Update the prefix (!)
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.25"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.18"
    }
  }
}

# Module imports

module "common" {
  source = "../../../../infra/common"
}

# Providers

variable "gcp_project_id" {
  description = "GCP project ID."
  type        = string
}

provider "google" {
  project = var.gcp_project_id
  region  = module.common.gcp_primary_location
}

# Cloudflare provider for managing DNS records

variable "cloudflare_api_token" {
  description = "Cloudflare API token."
  type        = string
  sensitive   = true
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
