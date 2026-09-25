output "tunnel_token" {
  description = "Token passed to cloudflared. modules/aws stores it in SSM"
  value       = data.cloudflare_zero_trust_tunnel_cloudflared_token.server.token
  sensitive   = true
}

output "vpc_service_id" {
  description = "service_id for vpc_services in src/front/wrangler.jsonc"
  value       = cloudflare_connectivity_directory_service.server.service_id
}

output "e2e_access_client" {
  description = "Service token for E2E to pass Access. null in environments without Access (prod). modules/aws stores it in SSM"
  value = local.access_enabled ? {
    client_id     = cloudflare_zero_trust_access_service_token.e2e[0].client_id
    client_secret = cloudflare_zero_trust_access_service_token.e2e[0].client_secret
  } : null
  sensitive = true
}

output "turnstile_site_key" {
  description = "Passed as VITE_TURNSTILE_SITE_KEY when building front. null in environments without Turnstile"
  value       = var.turnstile_enabled ? cloudflare_turnstile_widget.site[0].sitekey : null
}

output "turnstile_secret_key" {
  description = "Stored in the Worker secret (TURNSTILE_SECRET_KEY). null in environments without Turnstile"
  value       = var.turnstile_enabled ? cloudflare_turnstile_widget.site[0].secret : null
  sensitive   = true
}

output "access_enabled" {
  description = "Whether Cloudflare Access is applied. Used by make e2e-remote to decide whether to read the service token"
  value       = local.access_enabled
}
