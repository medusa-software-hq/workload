# Resources

# This repository
resource "github_repository" "this" {
  name        = module.common.gh_repo_name
  description = "Variant: ${module.common.project_variant}"

  # The repository is temporarily public
  visibility = "public"

  is_template = false

  has_discussions = false
  has_issues      = true
  has_projects    = false
  has_wiki        = false

  allow_merge_commit = true
  allow_squash_merge = false
  allow_rebase_merge = false

  allow_forking          = true
  allow_auto_merge       = true
  delete_branch_on_merge = true
}

# If the repo was created first, it has to be imported:
# terraform import github_repository.this $GH_REPO_NAME

locals {
  # GitHub Actions integration ID (discovered manually)
  gh_actions_integration_id = 15368

  check_workflows_job_name          = "workflows"
  check_infra_job_name              = "infra"
  check_web_infra_job_name          = "web (infra)"
  check_web_domain_mapping_job_name = "web (domain mapping)"
  check_web_spa_job_name            = "web (SPA)"
  check_backend_infra_job_name      = "backend (infra)"
  check_api_impl_job_name           = "api (implementation)"
  check_cli_job_name                = "cli"
  check_docker_connector_job_name   = "docker-connector"
}

# Branch protection ruleset for trunk branches
resource "github_repository_ruleset" "trunk_branches" {
  name        = "Trunk branches"
  repository  = github_repository.this.name
  target      = "branch"
  enforcement = "active"

  conditions {
    ref_name {
      include = ["refs/heads/trunk/*"]
      exclude = []
    }
  }

  rules {
    creation                = true
    update                  = false
    deletion                = true
    required_linear_history = false
    required_signatures     = true
    non_fast_forward        = true # Block force pushes

    pull_request {
      allowed_merge_methods = ["merge"]
    }

    required_status_checks {
      required_check {
        context        = "${local.check_workflows_job_name} / Lint GitHub workflows"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_infra_job_name} / Check Terraform formatting"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_web_infra_job_name} / Check Terraform configuration"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_web_domain_mapping_job_name} / Check Terraform configuration"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_web_spa_job_name} / Build frontend"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_web_spa_job_name} / Check Caddyfile"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_web_spa_job_name} / Build Docker image"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_backend_infra_job_name} / Check Terraform configuration"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_api_impl_job_name} / Check service"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_cli_job_name} / Check CLI"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "${local.check_docker_connector_job_name} / Check docker-connector"
        integration_id = local.gh_actions_integration_id
      }

      strict_required_status_checks_policy = true
    }
  }
}

# Allow GitHub Actions from this repository to run
resource "github_actions_repository_permissions" "this" {
  repository      = github_repository.this.name
  enabled         = true
  allowed_actions = "all"
}

# Deployment environments for the prod/staging split (M5-02). Each holds the environment-scoped
# CI/CD variables (GCP project id, API URL, CI/CD SA, OAuth client id, …) that distinguish a
# staging deploy from a prod one — see infra/github-variables.tf. Required reviewers are
# Enterprise-only for private repos, so any promotion gate is a job dependency (M5-04's
# deploy-prod needs staging smoke), not an Environment reviewer. The Environment still earns its
# keep: deployment tracking, environment-scoped variables, and a branch policy that restricts
# deploys to the trunk.
resource "github_repository_environment" "production" {
  repository  = github_repository.this.name
  environment = "production"

  deployment_branch_policy {
    protected_branches     = false
    custom_branch_policies = true
  }
}

# Only the trunk may deploy to production.
resource "github_repository_environment_deployment_policy" "production_trunk" {
  repository     = github_repository.this.name
  environment    = github_repository_environment.production.environment
  branch_pattern = module.common.gh_default_branch_name
}

resource "github_repository_environment" "staging" {
  repository  = github_repository.this.name
  environment = "staging"

  deployment_branch_policy {
    protected_branches     = false
    custom_branch_policies = true
  }
}

# Only the trunk may deploy to staging.
resource "github_repository_environment_deployment_policy" "staging_trunk" {
  repository     = github_repository.this.name
  environment    = github_repository_environment.staging.environment
  branch_pattern = module.common.gh_default_branch_name
}
