# OIDC and roles for GitHub Actions to access AWS.
#
# - plan role (short-link-ci-plan): for terraform plan on PRs. Read-only (except reading/writing the state lock file).
#   Can only be assumed from the GitHub Environment `plan`.
# - deploy roles (short-link-ci-deploy-<env>): one per environment. Can only be assumed from that environment's Environment.
#   The Environment restricts which branches/tags can deploy (infra/terraform/github).
#
# Who can assume is restricted by the sub of the OIDC token. This repository uses IDs instead of names in sub
# (use_immutable_subject), so it has the form repo:<owner>@<owner_id>/<repo>@<repo_id>:environment:<name>.
# Renaming does not change the ID, and a different repository created with the same name does not match it.
#
# To prevent privilege escalation:
# - deploy role names are kept out of the pattern of environment roles (short-link-<env>-*) that the deploy role can touch
# - environment roles (task execution role / task role) get a per-environment permissions boundary, and the deploy role can only
#   create roles with the boundary and cannot change it. Whatever policies are attached, a role never exceeds the boundary
# - other environments' state / SSM / ECS are off limits via Deny

locals {
  github_oidc_sub_prefix = "repo:u-na-gi@72393752/short-link-service@1385659227"
  envs                   = toset(["develop", "staging", "prod"])
  tfstate_bucket         = "short-link-service-tfstate-0b102b6e"
  account_id             = data.aws_caller_identity.current.account_id
  region                 = "ap-northeast-1"

  # "Other environments" as seen from each environment
  other_envs = { for env in local.envs : env => setsubtract(local.envs, [env]) }
}

data "aws_caller_identity" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# For each GitHub Environment name, trust only OIDC tokens coming from it
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

# --- Permissions boundary for environment roles ----------------------------------
# Attached to the task execution role / task role in modules/aws. Only what ECS needs to handle that environment's images, logs,
# and secrets. The task role (the app) uses none of it, but the same boundary sets its upper limit

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
      # Even plan writes and deletes the lock file (*.tflock) with the S3 backend
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::${local.tfstate_bucket}/*.tflock"
      },
      # GitHub settings (which contain the Cloudflare tokens for CI) and the bootstrap state are not readable from PRs
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

# For everything other than IAM (VPC / ECS / ECR / SSM / CloudWatch Logs / S3 state), start from PowerUser and narrow it with the Deny below
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
      # Manage that environment's roles (short-link-<env>-*). As long as the boundary is attached, nothing attached can exceed it
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
      # Creating roles or changing their boundary is allowed only when attaching that environment's boundary
      {
        Sid      = "CreateEnvRolesWithBoundary"
        Effect   = "Allow"
        Action   = ["iam:CreateRole", "iam:PutRolePermissionsBoundary"]
        Resource = "arn:aws:iam::${local.account_id}:role/short-link-${each.key}-*"
        Condition = {
          StringEquals = { "iam:PermissionsBoundary" = aws_iam_policy.ecs_task_boundary[each.key].arn }
        }
      },
      # ecspresso passes roles to task definitions. They can only be passed to ECS tasks
      {
        Sid      = "PassEnvRolesToEcsTasks"
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = "arn:aws:iam::${local.account_id}:role/short-link-${each.key}-*"
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      # Only its own environment's state is writable
      {
        Sid         = "WriteOnlyOwnState"
        Effect      = "Deny"
        Action      = ["s3:PutObject", "s3:DeleteObject"]
        NotResource = "arn:aws:s3:::${local.tfstate_bucket}/envs/${each.key}/*"
      },
      # Other environments, GitHub settings, and bootstrap state are not readable (shared is readable because the ECR URL is read from it)
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
      # No access to other environments' secrets and ECS
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
