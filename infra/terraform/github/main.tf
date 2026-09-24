# GitHub のリポジトリ設定: CI の Environment (develop / staging / prod / plan) と、ブランチ・タグのルール。
#
# CI に自分のシークレットを書き換えさせないよう、これは CI ではなく手元から apply する:
#
#   GITHUB_TOKEN=$(gh auth token) terraform apply
#
# シークレットの値 (CI 用の Cloudflare トークンなど) は infra/.envrc.local の TF_VAR_* から渡す。

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
  description = "develop / staging の Cloudflare Access で許可するメールアドレス (CI の terraform apply / plan が使う)"
  type        = string
  sensitive   = true
}

variable "ci_cloudflare_deploy_token" {
  description = "CI の apply / deploy 用の Cloudflare API トークン (Edit 系)"
  type        = string
  sensitive   = true
}

variable "ci_cloudflare_plan_token" {
  description = "PR の plan 用の Cloudflare API トークン (Read だけ)"
  type        = string
  sensitive   = true
}

# CI のロール (infra/terraform/shared/ci.tf)
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

  # ロールの ARN にはアカウント ID が入るので、変数ではなくシークレットにしてログで伏せる
  common_secrets = {
    CLOUDFLARE_ACCOUNT_ID = var.cloudflare_account_id
  }

  deploy_envs = {
    develop = { branches = ["develop"], tags = [], reviewers = [], access = true }
    staging = { branches = ["main"], tags = [], reviewers = [], access = true }
    # 本番はタグ (v*) からだけ、オーナーの承認を待ってから
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

# PR の terraform plan 用。フォークからの PR にはシークレットが渡らない (GitHub の仕様) ので、
# ブランチは制限しない。ロールは読み取りだけ、Cloudflare のトークンも Read だけ
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

# main / develop は消させない・履歴を書き換えさせない。オーナー (admin) は例外
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
    actor_id    = 5 # リポジトリの admin ロール
    actor_type  = "RepositoryRole"
    bypass_mode = "always"
  }

  rules {
    deletion         = true
    non_fast_forward = true
  }
}

# 本番リリースのタグ (v*) を作れる・動かせる・消せるのはオーナー (admin) だけ
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
