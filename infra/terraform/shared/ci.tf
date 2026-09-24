# GitHub Actions から AWS に入るための OIDC とロール。
#
# - plan ロール (short-link-ci-plan): PR の terraform plan 用。読み取りだけ (state のロックファイルの読み書きは除く)。
#   GitHub の Environment `plan` からだけ引き受けられる。
# - deploy ロール (short-link-ci-deploy-<env>): 環境ごと。その環境の Environment からだけ引き受けられる。
#   Environment 側でデプロイできるブランチ・タグを絞っている (infra/terraform/github)。
#
# 引き受け元は OIDC トークンの sub で絞る。このリポジトリは sub に名前ではなく ID を使う設定
# (use_immutable_subject) なので、repo:<owner>@<owner_id>/<repo>@<repo_id>:environment:<name> の形になる。
# 名前を変えても、同じ名前で別のリポジトリを作られても、ID は変わらない / 一致しない。
#
# 権限昇格を防ぐため:
# - deploy ロールの名前は、deploy ロールが触れる環境のロール (short-link-<env>-*) のパターンに入れない
# - 環境のロール (タスク実行ロール / タスクロール) には環境ごとの権限境界を付け、deploy ロールは境界付きでしか
#   ロールを作れない・境界を変えられないようにする。ロールにどんなポリシーを付けても、境界より強くはならない
# - 他の環境の state / SSM / ECS には Deny で触らせない

locals {
  github_oidc_sub_prefix = "repo:u-na-gi@72393752/short-link-service@1385659227"
  envs                   = toset(["develop", "staging", "prod"])
  tfstate_bucket         = "short-link-service-tfstate-0b102b6e"
  account_id             = data.aws_caller_identity.current.account_id
  region                 = "ap-northeast-1"

  # 各環境から見た「ほかの環境」
  other_envs = { for env in local.envs : env => setsubtract(local.envs, [env]) }
}

data "aws_caller_identity" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# GitHub の Environment 名ごとに、そこから来た OIDC トークンだけを信頼する
data "aws_iam_policy_document" "github_assume" {
  for_each = setunion(local.envs, ["plan"])

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.github_oidc_sub_prefix}:environment:${each.key}"]
    }
  }
}

# --- 環境のロールの権限境界 ---------------------------------------------------
# modules/aws のタスク実行ロール / タスクロールに付ける。ECS がその環境のイメージ・ログ・シークレットを扱うのに
# 要るものだけ。タスクロール (アプリ) は何も使わないが、同じ境界で上限を決める

resource "aws_iam_policy" "ecs_task_boundary" {
  for_each = local.envs

  name        = "short-link-${each.key}-ecs-task-boundary"
  description = "Upper bound of permissions for ECS task roles of ${each.key}"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
        Resource = aws_ecr_repository.server.arn
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "arn:aws:logs:${local.region}:${local.account_id}:log-group:/ecs/short-link-${each.key}/*"
      },
      {
        Effect   = "Allow"
        Action   = "ssm:GetParameters"
        Resource = "arn:aws:ssm:${local.region}:${local.account_id}:parameter/short-link-${each.key}/*"
      },
    ]
  })
}

# --- plan ---------------------------------------------------------------------

resource "aws_iam_role" "ci_plan" {
  name                 = "short-link-ci-plan"
  assume_role_policy   = data.aws_iam_policy_document.github_assume["plan"].json
  max_session_duration = 7200
}

resource "aws_iam_role_policy_attachment" "ci_plan_read_only" {
  role       = aws_iam_role.ci_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy" "ci_plan" {
  name = "tfstate"
  role = aws_iam_role.ci_plan.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # plan でも S3 backend はロックファイル (*.tflock) を書いて消す
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::${local.tfstate_bucket}/*.tflock"
      },
      # GitHub の設定 (CI 用の Cloudflare トークンが入っている) と bootstrap の state は PR から読ませない
      {
        Effect = "Deny"
        Action = "s3:GetObject"
        Resource = [
          "arn:aws:s3:::${local.tfstate_bucket}/github/*",
          "arn:aws:s3:::${local.tfstate_bucket}/bootstrap/*",
        ]
      },
    ]
  })
}

# --- deploy -------------------------------------------------------------------

resource "aws_iam_role" "ci_deploy" {
  for_each = local.envs

  name                 = "short-link-ci-deploy-${each.key}"
  assume_role_policy   = data.aws_iam_policy_document.github_assume[each.key].json
  max_session_duration = 7200
}

# IAM 以外 (VPC / ECS / ECR / SSM / CloudWatch Logs / S3 の state) は PowerUser を土台にし、下の Deny で絞る
resource "aws_iam_role_policy_attachment" "ci_deploy_power_user" {
  for_each = local.envs

  role       = aws_iam_role.ci_deploy[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

resource "aws_iam_role_policy" "ci_deploy" {
  for_each = local.envs

  name = "env-scope"
  role = aws_iam_role.ci_deploy[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # その環境のロール (short-link-<env>-*) を管理する。境界が付いている限り、何を付けても境界より強くならない
      {
        Sid    = "ManageEnvRoles"
        Effect = "Allow"
        Action = [
          "iam:GetRole", "iam:DeleteRole", "iam:UpdateRole", "iam:UpdateAssumeRolePolicy",
          "iam:TagRole", "iam:UntagRole", "iam:ListRoleTags",
          "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:GetRolePolicy", "iam:ListRolePolicies",
          "iam:AttachRolePolicy", "iam:DetachRolePolicy", "iam:ListAttachedRolePolicies",
          "iam:ListInstanceProfilesForRole",
        ]
        Resource = "arn:aws:iam::${local.account_id}:role/short-link-${each.key}-*"
      },
      # ロールを作る・境界を付け替えるのは、その環境の境界を付けるときだけ
      {
        Sid      = "CreateEnvRolesWithBoundary"
        Effect   = "Allow"
        Action   = ["iam:CreateRole", "iam:PutRolePermissionsBoundary"]
        Resource = "arn:aws:iam::${local.account_id}:role/short-link-${each.key}-*"
        Condition = {
          StringEquals = { "iam:PermissionsBoundary" = aws_iam_policy.ecs_task_boundary[each.key].arn }
        }
      },
      # ecspresso がタスク定義にロールを渡す。渡せるのは ECS のタスクにだけ
      {
        Sid      = "PassEnvRolesToEcsTasks"
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = "arn:aws:iam::${local.account_id}:role/short-link-${each.key}-*"
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      # state は自分の環境のものだけ書ける
      {
        Sid         = "WriteOnlyOwnState"
        Effect      = "Deny"
        Action      = ["s3:PutObject", "s3:DeleteObject"]
        NotResource = "arn:aws:s3:::${local.tfstate_bucket}/envs/${each.key}/*"
      },
      # ほかの環境・GitHub の設定・bootstrap の state は読めない (shared は ECR の URL を読むので読める)
      {
        Sid    = "ReadNoOtherState"
        Effect = "Deny"
        Action = "s3:GetObject"
        Resource = concat(
          [for env in local.other_envs[each.key] : "arn:aws:s3:::${local.tfstate_bucket}/envs/${env}/*"],
          [
            "arn:aws:s3:::${local.tfstate_bucket}/github/*",
            "arn:aws:s3:::${local.tfstate_bucket}/bootstrap/*",
          ],
        )
      },
      # ほかの環境のシークレットと ECS には触れない
      {
        Sid      = "NoOtherEnvSecrets"
        Effect   = "Deny"
        Action   = "ssm:*"
        Resource = [for env in local.other_envs[each.key] : "arn:aws:ssm:${local.region}:${local.account_id}:parameter/short-link-${env}/*"]
      },
      {
        Sid    = "NoOtherEnvEcs"
        Effect = "Deny"
        Action = "ecs:*"
        Resource = flatten([for env in local.other_envs[each.key] : [
          "arn:aws:ecs:${local.region}:${local.account_id}:cluster/short-link-${env}",
          "arn:aws:ecs:${local.region}:${local.account_id}:service/short-link-${env}/*",
          "arn:aws:ecs:${local.region}:${local.account_id}:task/short-link-${env}/*",
          "arn:aws:ecs:${local.region}:${local.account_id}:task-definition/short-link-${env}-*",
        ]])
      },
    ]
  })
}

output "ci_plan_role_arn" {
  value = aws_iam_role.ci_plan.arn
}

output "ci_deploy_role_arns" {
  value = { for env, role in aws_iam_role.ci_deploy : env => role.arn }
}

output "ecs_task_boundary_arns" {
  value = { for env, policy in aws_iam_policy.ecs_task_boundary : env => policy.arn }
}
