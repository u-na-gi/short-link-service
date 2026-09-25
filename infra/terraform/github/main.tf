# GitHub repository settings: CI Environments (develop / staging / prod / plan) and branch/tag rules.
#
# So that CI cannot rewrite its own secrets, this is applied locally, not from CI:
#
#   GITHUB_TOKEN=$(gh auth token) terraform apply
#
# Secret values (such as the Cloudflare tokens for CI) are passed via TF_VAR_* in infra/.envrc.local.

terraform {
  required_version = ">= 1.16"

  required_providers {
    github = {
      source  = "integrations/github"
      version = "~> 6.13"
    }
  }

  backend "s3" {
    bucket       = "short-link-service-tfstate-0b102b6e"
    key          = "github/terraform.tfstate"
    region       = "ap-northeast-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "github" {
  owner = "u-na-gi"
}

variable "cloudflare_account_id" {
  type      = string
  sensitive = true
}

variable "access_allowed_email" {
  description = "Email address allowed by Cloudflare Access in develop / staging (used by terraform apply / plan in CI)"
  type        = string
  sensitive   = true
}

variable "ci_cloudflare_deploy_token" {
  description = "Cloudflare API token for apply / deploy in CI (Edit permissions)"
  type        = string
  sensitive   = true
}

variable "ci_cloudflare_plan_token" {
  description = "Cloudflare API token for plan on PRs (Read only)"
  type        = string
  sensitive   = true
}

# CI roles (infra/terraform/shared/ci.tf)
data "terraform_remote_state" "shared" {
  backend = "s3"
  config = {
    bucket = "short-link-service-tfstate-0b102b6e"
    key    = "shared/terraform.tfstate"
    region = "ap-northeast-1"
  }
}

data "github_user" "owner" {
  username = "u-na-gi"
}

locals {
  repository = "short-link-service"

  # The role ARN contains the account ID, so make it a secret instead of a variable to mask it in logs
  common_secrets = {
    CLOUDFLARE_ACCOUNT_ID = var.cloudflare_account_id
  }

  deploy_envs = {
    develop = { branches = ["develop"], tags = [], reviewers = [], access = true }
    staging = { branches = ["main"], tags = [], reviewers = [], access = true }
    # Production only from tags (v*), after waiting for owner approval
    prod = { branches = [], tags = ["v*"], reviewers = [tonumber(data.github_user.owner.id)], access = false }
  }
}

module "deploy_environment" {
  source   = "../modules/github"
  for_each = local.deploy_envs

  repository        = local.repository
  environment       = each.key
  branch_patterns   = each.value.branches
  tag_patterns      = each.value.tags
  reviewer_user_ids = each.value.reviewers

  secrets = merge(
    local.common_secrets,
    {
      AWS_ROLE_ARN         = data.terraform_remote_state.shared.outputs.ci_deploy_role_arns[each.key]
      CLOUDFLARE_API_TOKEN = var.ci_cloudflare_deploy_token
    },
    each.value.access ? { TF_VAR_access_allowed_email = var.access_allowed_email } : {},
  )
}

# For terraform plan on PRs. PRs from forks do not get secrets (GitHub behavior), so
# branches are not restricted. The role is read-only and the Cloudflare token is Read only too
module "plan_environment" {
  source = "../modules/github"

  repository  = local.repository
  environment = "plan"

  secrets = merge(local.common_secrets, {
    AWS_ROLE_ARN                = data.terraform_remote_state.shared.outputs.ci_plan_role_arn
    CLOUDFLARE_API_TOKEN        = var.ci_cloudflare_plan_token
    TF_VAR_access_allowed_email = var.access_allowed_email
  })
}

# main / develop cannot be deleted or have history rewritten. The owner (admin) is exempt
resource "github_repository_ruleset" "branches" {
  name        = "protect-deploy-branches"
  repository  = local.repository
  target      = "branch"
  enforcement = "active"

  conditions {
    ref_name {
      include = ["refs/heads/main", "refs/heads/develop"]
      exclude = []
    }
  }

  bypass_actors {
    actor_id    = 5 # repository admin role
    actor_type  = "RepositoryRole"
    bypass_mode = "always"
  }

  rules {
    deletion         = true
    non_fast_forward = true
  }
}

# Only the owner (admin) can create, move, or delete production release tags (v*)
resource "github_repository_ruleset" "release_tags" {
  name        = "protect-release-tags"
  repository  = local.repository
  target      = "tag"
  enforcement = "active"

  conditions {
    ref_name {
      include = ["refs/tags/v*"]
      exclude = []
    }
  }

  bypass_actors {
    actor_id    = 5
    actor_type  = "RepositoryRole"
    bypass_mode = "always"
  }

  rules {
    creation = true
    update   = true
    deletion = true
  }
}
