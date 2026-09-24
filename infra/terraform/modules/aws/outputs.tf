output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "server_log_group_name" {
  value = aws_cloudwatch_log_group.server.name
}

output "play_http_secret_key_parameter_arn" {
  value = aws_ssm_parameter.play_http_secret_key.arn
}

output "task_execution_role_arn" {
  value = aws_iam_role.task_execution.arn
}

output "task_role_arn" {
  value = aws_iam_role.task.arn
}

output "task_security_group_id" {
  value = aws_security_group.task.id
}


output "tunnel_token_parameter_arn" {
  value = aws_ssm_parameter.tunnel_token.arn
}
