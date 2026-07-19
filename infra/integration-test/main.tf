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
    "secretmanager.googleapis.com",
    "storage.googleapis.com",
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

# A target service account for the *manual* end-to-end hand test (M3-05/07 AC 1: register →
# approve → grant → `workload run` against the live broker).
#
# Deliberately separate from it-reader/it-no-reader above. Those exist for the automated workflow
# and trust only the federated CI identity; giving them prod-broker trust as well would blur what
# each fixture proves, and would leave a throwaway test account trusting production. This one is
# honest about being exactly that: an opt-in from the test project to the *production* broker.
#
# It goes through the real workload-impersonation module rather than hand-rolled bindings, so the
# hand test exercises the same path a real consumer follows — which is the point of the exercise.
resource "google_service_account" "hand_test" {
  project      = var.gcp_project_id
  account_id   = "it-handtest"
  display_name = "Integration test: hand-test target SA (production broker impersonates this)"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

# A secret for the hand test to resolve, so the run proves the *payload* arrived and not just that
# the container started. The value is deliberately non-secret and committed here: it exists to be
# compared against a fingerprint, and a fixture that pretended to be sensitive would only invite
# someone to treat a real secret this way.
resource "google_secret_manager_secret" "hand_test_demo" {
  project   = var.gcp_project_id
  secret_id = "hello-workload-demo"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret_version" "hand_test_demo" {
  secret      = google_secret_manager_secret.hand_test_demo.id
  secret_data = local.hand_test_demo_secret_value
}

locals {
  # sha256 of this is what hello-workload prints, so the hand test can verify end to end.
  hand_test_demo_secret_value = "hello-workload-demo-secret-value"
}

# A sample GCS object for the hand test to *fetch from inside the container*, using nothing but
# `gcloud storage` and the brokered credential the metadata server serves — the way any real
# workload reads a bucket. Where the secret proves worker-side resolution (M2), this proves the
# in-container, GCE-native path end to end: metadata server -> ADC -> Storage API -> a live object.
# Bucket name is derived from the (globally-unique) project id; content is a committed non-secret
# value so the run can verify it by fingerprint.
resource "google_storage_bucket" "hand_test_probe" {
  project                     = var.gcp_project_id
  name                        = "${var.gcp_project_id}-hello-probe"
  location                    = module.common.gcp_primary_location
  uniform_bucket_level_access = true
  force_destroy               = true

  depends_on = [google_project_service.apis["storage.googleapis.com"]]
}

resource "google_storage_bucket_object" "hand_test_probe" {
  bucket  = google_storage_bucket.hand_test_probe.name
  name    = "hello.txt"
  content = local.hand_test_probe_object_content
}

locals {
  # sha256 of this is what hello-workload prints for the fetched object — same verify-by-fingerprint
  # idea as the secret, but for a resource the container reads itself.
  hand_test_probe_object_content = "hello from a real GCS object, fetched via the metadata server\n"
}

# The hand-test SA can read that object (and only that). Direct grant rather than through the
# impersonation module — the module covers registry + secrets; this is the one bucket the fixture
# needs, and an explicit member reads clearer than a new module input for a throwaway fixture.
resource "google_storage_bucket_iam_member" "hand_test_can_read_probe" {
  bucket = google_storage_bucket.hand_test_probe.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.hand_test.email}"
}

module "hand_test_impersonation" {
  source = "../modules/workload-impersonation"

  service_account_id    = google_service_account.hand_test.name
  service_account_email = google_service_account.hand_test.email

  # Grants the SA roles/artifactregistry.reader on the fixture repo, so the backend can resolve the
  # image's digest and the worker can pull it — both as this account.
  artifact_repository_id = google_artifact_registry_repository.integration_test.id

  # ...and secretAccessor on the demo secret, which the worker resolves itself using the brokered
  # token. Exercises the module's other input on the same account.
  secret_ids = [google_secret_manager_secret.hand_test_demo.id]
}

# The hand test's negative case, and the reason it needs its own account: pointing a profile at
# it-no-reader would flag `binding_missing`, not `image_unresolvable` — the production broker can't
# impersonate that one at all, so it would never reach the registry and you'd be reading the wrong
# error. This account *is* impersonable by the broker and simply has no reader grant, so it isolates
# the missing grant exactly the way the automated pair does.
resource "google_service_account" "hand_test_no_reader" {
  project      = var.gcp_project_id
  account_id   = "it-handtest-no-reader"
  display_name = "Integration test: hand-test target SA WITHOUT artifactregistry.reader"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

module "hand_test_no_reader_impersonation" {
  source = "../modules/workload-impersonation"

  service_account_id    = google_service_account.hand_test_no_reader.name
  service_account_email = google_service_account.hand_test_no_reader.email

  # No artifact_repository_id on purpose: this is the whole experiment.
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

resource "github_actions_variable" "it_hello_image_repository" {
  repository    = data.github_repository.this.name
  variable_name = "IT_HELLO_IMAGE_REPOSITORY"
  value         = "${google_artifact_registry_repository.integration_test.registry_uri}/hello-workload"
}

output "integration_test_ci_sa_email" {
  description = "The identity the integration-test workflow federates into."
  value       = google_service_account.integration_test_ci.email
}

output "image_repository" {
  description = "The private image the test pulls."
  value       = "${google_artifact_registry_repository.integration_test.registry_uri}/busybox"
}

output "hand_test_profile_inputs" {
  description = "Everything to paste into the console's Create profile form for the manual end-to-end test."
  value = {
    target_service_account = google_service_account.hand_test.email
    container_image        = "${google_artifact_registry_repository.integration_test.registry_uri}/hello-workload:latest"
    secret_env_var = {
      DEMO_SECRET = "${google_secret_manager_secret.hand_test_demo.id}/versions/latest"
    }
    # Plain (non-secret) env. HELLO_PROBE_GCS tells the image which object to fetch with
    # `gcloud storage` — the in-container, metadata-server-authenticated resource read.
    env_var = {
      HELLO_PROBE_GCS = "gs://${google_storage_bucket.hand_test_probe.name}/${google_storage_bucket_object.hand_test_probe.name}"
    }
    # hello-workload prints a sha256 prefix per env var + for the fetched object; these are what a
    # correct run must show.
    expected_fingerprints = {
      DEMO_SECRET     = substr(sha256(local.hand_test_demo_secret_value), 0, 12)
      HELLO_PROBE_GCS = substr(sha256(local.hand_test_probe_object_content), 0, 12)
    }
  }
}

output "hand_test_negative_case_service_account" {
  description = "Same image, but a target SA with no artifactregistry.reader: the revision should flag image_unresolvable (NOT binding_missing — the broker can impersonate this one)."
  value       = google_service_account.hand_test_no_reader.email
}
