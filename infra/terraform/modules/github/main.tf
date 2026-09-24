# GitHub の Environment 1 つ分: デプロイできるブランチ・タグ、承認者、変数とシークレット。
# シークレットの値は Terraform の state (S3、暗号化) にも載る。

locals {
  restricted = length(var.branch_patterns) + length(var.tag_patterns) > 0
}

resource "github_repository_environment" "this" {
  repository  = var.repository
  environment = var.environment

  # 承認者が自分 1 人なので、自分のデプロイを自分で承認できるようにする
  prevent_self_review = false
  # 承認が要る環境 (prod) は、admin でも承認を飛ばせない
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
  # 名前は秘密ではないので for_each のキーに使う (値は sensitive のまま)
  for_each = toset(nonsensitive(keys(var.secrets)))

  repository      = var.repository
  environment     = github_repository_environment.this.environment
  secret_name     = each.value
  plaintext_value = var.secrets[each.value]
}
