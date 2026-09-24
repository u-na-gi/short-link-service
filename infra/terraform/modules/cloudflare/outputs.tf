output "tunnel_token" {
  description = "cloudflared に渡すトークン。modules/aws が SSM に入れる"
  value       = data.cloudflare_zero_trust_tunnel_cloudflared_token.server.token
  sensitive   = true
}

output "vpc_service_id" {
  description = "src/front/wrangler.jsonc の vpc_services の service_id"
  value       = cloudflare_connectivity_directory_service.server.service_id
}

output "e2e_access_client" {
  description = "E2E が Access を通るためのサービストークン。Access をかけない環境 (prod) では null。modules/aws が SSM に入れる"
  value = local.access_enabled ? {
    client_id     = cloudflare_zero_trust_access_service_token.e2e[0].client_id
    client_secret = cloudflare_zero_trust_access_service_token.e2e[0].client_secret
  } : null
  sensitive = true
}
