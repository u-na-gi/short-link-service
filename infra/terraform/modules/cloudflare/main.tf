# Cloudflare 側: Tunnel と、Worker から Tunnel の先の Play へ届くための VPC Service (Workers VPC)。
#
# cloudflared は ECS タスクのサイドカーで、Play とネットワーク名前空間 (awsvpc) を共有する。
# なので VPC Service の宛先は 127.0.0.1:9000。トークンは output で返し、modules/aws が SSM に入れる。
# Worker 自体は wrangler でデプロイする (src/front/wrangler.jsonc)。

resource "cloudflare_zero_trust_tunnel_cloudflared" "server" {
  account_id = var.account_id
  name       = local.name
  config_src = "cloudflare"
}

data "cloudflare_zero_trust_tunnel_cloudflared_token" "server" {
  account_id = var.account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.server.id
}

resource "cloudflare_connectivity_directory_service" "server" {
  account_id = var.account_id
  name       = local.name
  type       = "http"
  http_port  = 9000

  host = {
    ipv4 = "127.0.0.1"
    network = {
      tunnel_id = cloudflare_zero_trust_tunnel_cloudflared.server.id
    }
  }
}
