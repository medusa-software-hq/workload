# Configuration

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/workload/baseline/worker-mvp"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.25"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.8"
    }
  }
}

# Module imports

module "common" {
  source = "../../infra/common"
}

# Providers

variable "gcp_project_id" {
  description = "GCP project ID of the MAIN (already-deployed) workload project — used only to compute the existing broker service account's email deterministically."
  type        = string
}

provider "google" {
  project = module.common.gcp_meta_project_id
  region  = module.common.gcp_primary_location
}
