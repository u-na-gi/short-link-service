# ECS クラスタとタスクの SG。
#
# タスクは Fargate で、service とタスク定義は Terraform ではなく ecspresso が持つ。
# 外からの入口は Cloudflare Tunnel (サイドカーの cloudflared が外向きに張る) だけなので、SG の ingress は開けない。

resource "aws_ecs_cluster" "this" {
  name = local.name

  # 使うときにしか起動しないので、Container Insights の料金はかけない
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_security_group" "task" {
  name        = "${local.name}-task"
  description = "Server task (Fargate). No ingress; reached only via Cloudflare Tunnel."
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${local.name}-task" }
}

# ECR からのイメージ取得、CloudWatch Logs、SSM、cloudflared から Cloudflare への接続
resource "aws_vpc_security_group_egress_rule" "task_all" {
  security_group_id = aws_security_group.task.id
  description       = "ECR / CloudWatch Logs / SSM / Cloudflare Tunnel"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
