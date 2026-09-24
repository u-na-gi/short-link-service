# タスクが使うもの: ロググループ、秘密鍵 (SSM)、タスク実行ロール、タスクロール。
# タスク定義そのものは ecspresso が持ち、ここの ARN や名前を output 経由で読む。

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# server と cloudflared の両方のログを入れる (ストリームの接頭辞で分ける)
resource "aws_cloudwatch_log_group" "server" {
  name              = "/ecs/${local.name}/server"
  retention_in_days = 14
}

# Play の play.http.secret.key。値は tfstate にも載る (state のバケットは暗号化して公開を遮断している)
resource "random_password" "play_http_secret_key" {
  length  = 64
  special = false
}

resource "aws_ssm_parameter" "play_http_secret_key" {
  name  = "/${local.name}/server/play-http-secret-key"
  type  = "SecureString"
  value = random_password.play_http_secret_key.result
}

# cloudflared に渡す Tunnel のトークン。ECS が SSM から TUNNEL_TOKEN に注入する
resource "aws_ssm_parameter" "tunnel_token" {
  name  = "/${local.name}/cloudflared/tunnel-token"
  type  = "SecureString"
  value = var.tunnel_token
}

locals {
  # ecs-tasks にロールを渡すとき、自分のアカウントの ECS からだけ引き受けさせる (confused deputy 対策)
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

# ECS エージェントがイメージ取得・ログ送信・シークレット取得に使うロール
resource "aws_iam_role" "task_execution" {
  name               = "${local.name}-task-execution"
  assume_role_policy = local.ecs_tasks_assume_role_policy
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

# アプリ自身のロール。今は AWS の API を呼ばないので権限を付けない
resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = local.ecs_tasks_assume_role_policy
}
