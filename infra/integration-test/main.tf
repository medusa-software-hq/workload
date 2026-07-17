# Fixtures for the registry-auth integration test (`.github/workflows/integration-test-registry-auth.yml`).
#
# What this exists to prove: a pull authenticated with a *brokered* token — one minted by
# impersonating a profile's target service account — really can read a private Artifact Registry
# image, and really is refused when that account lacks roles/artifactregistry.reader. Everything
# else about that path is unit- and contract-tested; only the live GCP hop wasn't.
#
# The test deliberately does NOT authenticate as the CI/CD service account. That account is close to
# an admin here, so a pull as *it* would succeed regardless of the reader grant and the negative case
# could never fail — a permanently green lie. Instead the CI/CD SA only *impersonates* the two target
# accounts below (exactly as the broker does), so the token under test is a real target-SA token.
#
# Applied by hand against the throwaway test project — like worker-mvp/infra, this is not wired to an
# apply workflow. See README.md.

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/workload/baseline/integration-test"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.25"
    }
    github = {
      source  = "integrations/github"
      version = "~> 6.11"
    }
  }
}

module "common" {
  source = "../common"
}

variable "gcp_project_id" {
  description = "The throwaway GCP project holding the integration-test fixtures ('workload - test')."
  type        = string
  default     = "ms-workload-test-78f9932a"
}

provider "google" {
  project = var.gcp_project_id
  region  = module.common.gcp_primary_location
}

provider "github" {
  owner = module.common.gh_organization_name
}

data "github_repository" "this" {
  full_name = "${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}

# The shared Workload Identity pool GitHub Actions federates into — the same one the deploy jobs use.
data "terraform_remote_state" "shared" {
  backend = "gcs"

  config = {
    bucket = module.common.gcp_terraform_state_bucket_name
    prefix = "shared/foundation"
  }
}

locals {
  gcp_cicd_wi_pool_name = data.terraform_remote_state.shared.outputs.gcp_cicd_wi_pool_name
}

resource "google_project_service" "apis" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
  ])

  project            = var.gcp_project_id
  service            = each.key
  disable_on_destroy = false
}

# The Actions identity for this test project. Only a front door: it can push the fixture image and
# impersonate the two target SAs below, and nothing else.
resource "google_service_account" "integration_test_ci" {
  project      = var.gcp_project_id
  account_id   = "integration-test-ci"
  display_name = "Registry-auth integration test CI"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

resource "google_service_account_iam_member" "integration_test_ci_wi_user" {
  service_account_id = google_service_account.integration_test_ci.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${local.gcp_cicd_wi_pool_name}/attribute.repository/${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}

# The private repository the test pulls from.
resource "google_artifact_registry_repository" "integration_test" {
  project       = var.gcp_project_id
  location      = module.common.gcp_primary_location
  repository_id = "integration-test"
  description   = "Private images for the registry-auth integration test."
  format        = "DOCKER"

  depends_on = [google_project_service.apis["artifactregistry.googleapis.com"]]
}

# The CI identity pushes the fixture image; it never pulls in the test.
resource "google_artifact_registry_repository_iam_member" "integration_test_ci_writer" {
  project    = var.gcp_project_id
  location   = google_artifact_registry_repository.integration_test.location
  repository = google_artifact_registry_repository.integration_test.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.integration_test_ci.email}"
}

# Two stand-ins for a profile's target service account: identical except for the reader grant, which
# is the single variable the test is actually measuring.

resource "google_service_account" "reader" {
  project      = var.gcp_project_id
  account_id   = "it-reader"
  display_name = "Integration test: target SA WITH artifactregistry.reader"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

resource "google_service_account" "no_reader" {
  project      = var.gcp_project_id
  account_id   = "it-no-reader"
  display_name = "Integration test: target SA WITHOUT artifactregistry.reader"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

# The only difference between the two accounts.
resource "google_artifact_registry_repository_iam_member" "reader_can_read" {
  project    = var.gcp_project_id
  location   = google_artifact_registry_repository.integration_test.location
  repository = google_artifact_registry_repository.integration_test.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.reader.email}"
}

# Let the Actions identity mint tokens for both — the same roles/iam.serviceAccountTokenCreator the
# broker's runtime SA holds on a real target SA (see infra/modules/workload-impersonation). This is
# what makes the test exercise the production mechanism rather than a simulation of it.
resource "google_service_account_iam_member" "ci_can_impersonate_reader" {
  service_account_id = google_service_account.reader.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.integration_test_ci.email}"
}

resource "google_service_account_iam_member" "ci_can_impersonate_no_reader" {
  service_account_id = google_service_account.no_reader.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.integration_test_ci.email}"
}

# Actions variables consumed by the integration-test workflow.

resource "github_actions_variable" "it_ci_sa_email" {
  repository    = data.github_repository.this.name
  variable_name = "IT_CI_SA_EMAIL"
  value         = google_service_account.integration_test_ci.email
}

resource "github_actions_variable" "it_reader_sa_email" {
  repository    = data.github_repository.this.name
  variable_name = "IT_READER_SA_EMAIL"
  value         = google_service_account.reader.email
}

resource "github_actions_variable" "it_no_reader_sa_email" {
  repository    = data.github_repository.this.name
  variable_name = "IT_NO_READER_SA_EMAIL"
  value         = google_service_account.no_reader.email
}

resource "github_actions_variable" "it_registry_hostname" {
  repository    = data.github_repository.this.name
  variable_name = "IT_REGISTRY_HOSTNAME"
  value         = split("/", google_artifact_registry_repository.integration_test.registry_uri)[0]
}

resource "github_actions_variable" "it_image_repository" {
  repository    = data.github_repository.this.name
  variable_name = "IT_IMAGE_REPOSITORY"
  value         = "${google_artifact_registry_repository.integration_test.registry_uri}/busybox"
}

output "integration_test_ci_sa_email" {
  description = "The identity the integration-test workflow federates into."
  value       = google_service_account.integration_test_ci.email
}

output "image_repository" {
  description = "The private image the test pulls."
  value       = "${google_artifact_registry_repository.integration_test.registry_uri}/busybox"
}
