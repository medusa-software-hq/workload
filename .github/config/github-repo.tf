# Resources

# This repository
resource "github_repository" "this" {
  name        = module.common.gh_repo_name
  description = "Variant: ${module.common.project_variant}"
  visibility  = "private"

  is_template = false

  has_discussions = false
  has_issues      = false
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
}

# Branch protection ruleset for the default branch
resource "github_repository_ruleset" "default_branch" {
  name        = "Default branch"
  repository  = github_repository.this.name
  target      = "branch"
  enforcement = "active"

  conditions {
    ref_name {
      include = ["~DEFAULT_BRANCH"]
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
