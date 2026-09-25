# ECS cluster and the task SG.
#
# Tasks run on Fargate; the service and task definition are owned by ecspresso, not Terraform.
# The only entry point from outside is Cloudflare Tunnel (the cloudflared sidecar connects outbound), so the SG opens no ingress.

resource "aws_ecs_cluster" "this" {
  name = local.name

  # It only runs when in use, so skip the cost of Container Insights
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

# Image pulls from ECR, CloudWatch Logs, SSM, and cloudflared connecting to Cloudflare
resource "aws_vpc_security_group_egress_rule" "task_all" {
  security_group_id = aws_security_group.task.id
  description       = "ECR / CloudWatch Logs / SSM / Cloudflare Tunnel"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
