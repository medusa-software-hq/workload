# Configuration

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/workload/baseline/root"
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
    github = {
      source  = "integrations/github"
      version = "~> 6.11"
    }
  }
}

# Module imports

module "common" {
  source = "./common"
}

# Providers

# Primary Google provider
provider "google" {
  project = module.common.gcp_meta_project_id
  region  = module.common.gcp_primary_location
}

# Secondary Google provider that bills API quota to this project. Required for
# APIs that need a quota project under user ADC (organization policy, Site
# Verification, …).
provider "google" {
  alias   = "quota_override"
  project = module.common.gcp_meta_project_id
  region  = module.common.gcp_primary_location

  user_project_override = true
  billing_project       = local.gcp_project_id
}

# GitHub provider for writing CI/CD Actions variables. The repository itself is
# managed by the .github/config root; here it is only referenced as data.

variable "gh_token" {
  description = "Organization-owned GitHub token."
  type        = string
  sensitive   = true
}

provider "github" {
  owner = module.common.gh_organization_name
  token = var.gh_token
}

data "github_repository" "this" {
  full_name = "${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}
