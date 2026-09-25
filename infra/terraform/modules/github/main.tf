# One GitHub Environment: deployable branches/tags, reviewers, variables, and secrets.
# Secret values are also stored in Terraform state (S3, encrypted).

locals {
  restricted = length(var.branch_patterns) + length(var.tag_patterns) > 0
}

resource "github_repository_environment" "this" {
  repository  = var.repository
  environment = var.environment

  # The only reviewer is me, so allow approving my own deployments
  prevent_self_review = false
  # Environments that need approval (prod) cannot skip it, even for admins
  can_admins_bypass = length(var.reviewer_user_ids) == 0

  dynamic "reviewers" {
    for_each = length(var.reviewer_user_ids) > 0 ? [1] : []
    content {
      users = var.reviewer_user_ids
    }
  }

  dynamic "deployment_branch_policy" {
    for_each = local.restricted ? [1] : []
    content {
      protected_branches     = false
      custom_branch_policies = true
    }
  }
}

resource "github_repository_environment_deployment_policy" "branch" {
  for_each = toset(var.branch_patterns)

  repository     = var.repository
  environment    = github_repository_environment.this.environment
  branch_pattern = each.value
}

resource "github_repository_environment_deployment_policy" "tag" {
  for_each = toset(var.tag_patterns)

  repository  = var.repository
  environment = github_repository_environment.this.environment
  tag_pattern = each.value
}

resource "github_actions_environment_variable" "this" {
  for_each = var.variables

  repository    = var.repository
  environment   = github_repository_environment.this.environment
  variable_name = each.key
  value         = each.value
}

resource "github_actions_environment_secret" "this" {
  # Names are not secret, so use them as for_each keys (values stay sensitive)
  for_each = toset(nonsensitive(keys(var.secrets)))

  repository      = var.repository
  environment     = github_repository_environment.this.environment
  secret_name     = each.value
  plaintext_value = var.secrets[each.value]
}
