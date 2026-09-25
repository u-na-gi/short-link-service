# What the task uses: log group, secret key (SSM), task execution role, task role.
# The task definition itself is owned by ecspresso, which reads the ARNs and names here through outputs.

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# Holds logs from both server and cloudflared (separated by stream prefix)
resource "aws_cloudwatch_log_group" "server" {
  name              = "/ecs/${local.name}/server"
  retention_in_days = 14
}

# Play's play.http.secret.key. The value is also stored in tfstate (the state bucket is encrypted and blocks public access)
resource "random_password" "play_http_secret_key" {
  length  = 64
  special = false
}

resource "aws_ssm_parameter" "play_http_secret_key" {
  name  = "/${local.name}/server/play-http-secret-key"
  type  = "SecureString"
  value = random_password.play_http_secret_key.result
}

# Tunnel token passed to cloudflared. ECS injects it from SSM into TUNNEL_TOKEN
resource "aws_ssm_parameter" "tunnel_token" {
  name  = "/${local.name}/cloudflared/tunnel-token"
  type  = "SecureString"
  value = var.tunnel_token
}

locals {
  # Permissions boundary for the environment's roles (infra/terraform/shared/ci.tf). The CI deploy role can only create roles
  # with this boundary, so whatever CI attaches to a role, it never gets more than ECS image pulls, logs, and that environment's secrets
  task_role_boundary_arn = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/${local.name}-ecs-task-boundary"

  # When passing the role to ecs-tasks, allow only ECS in our own account to assume it (confused deputy protection)
  ecs_tasks_assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:aws:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:*" }
      }
    }]
  })
}

# Role the ECS agent uses to pull images, send logs, and fetch secrets
resource "aws_iam_role" "task_execution" {
  name                 = "${local.name}-task-execution"
  assume_role_policy   = local.ecs_tasks_assume_role_policy
  permissions_boundary = local.task_role_boundary_arn
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name = "read-secrets"
  role = aws_iam_role.task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "ssm:GetParameters"
      Resource = [
        aws_ssm_parameter.play_http_secret_key.arn,
        aws_ssm_parameter.tunnel_token.arn,
      ]
    }]
  })
}

# The app's own role. It does not call AWS APIs for now, so no permissions are attached
resource "aws_iam_role" "task" {
  name                 = "${local.name}-task"
  assume_role_policy   = local.ecs_tasks_assume_role_policy
  permissions_boundary = local.task_role_boundary_arn
}
