# ecspresso reads these outputs via the tfstate plugin ({{ tfstate "output.xxx" }})

output "vpc_id" {
  value = module.aws.vpc_id
}

output "public_subnet_ids" {
  value = module.aws.public_subnet_ids
}

output "ecs_cluster_name" {
  value = module.aws.ecs_cluster_name
}

output "server_log_group_name" {
  value = module.aws.server_log_group_name
}

output "play_http_secret_key_parameter_arn" {
  value = module.aws.play_http_secret_key_parameter_arn
}

output "task_execution_role_arn" {
  value = module.aws.task_execution_role_arn
}

output "task_role_arn" {
  value = module.aws.task_role_arn
}

output "task_security_group_id" {
  value = module.aws.task_security_group_id
}

output "tunnel_token_parameter_arn" {
  value = module.aws.tunnel_token_parameter_arn
}

output "vpc_service_id" {
  value = module.cloudflare.vpc_service_id
}

output "public_base_url" {
  description = "Public URL of the site (Worker routes, target of make e2e-remote)"
  value       = local.public_base_url
}

output "shortener_base_url" {
  description = "Play's SHORTENER_BASE_URL (short URL base)"
  value       = local.public_base_url
}

output "turnstile_site_key" {
  value = module.cloudflare.turnstile_site_key
}

output "turnstile_secret_key" {
  value     = module.cloudflare.turnstile_secret_key
  sensitive = true
}

output "access_enabled" {
  value = module.cloudflare.access_enabled
}
