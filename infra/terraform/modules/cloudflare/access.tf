# Cloudflare Access (develop / staging)。Worker のホスト名の前でログインさせ、許可したメールアドレスだけ通す。
# ログインはメールに届くワンタイムコード (Zero Trust の既定のログイン方法)。
# prod は公開サービスなので access_allowed_email = null にしてかけない。
#
# E2E (runn) などの機械はサービストークンで通す。CF-Access-Client-Id / CF-Access-Client-Secret ヘッダを付ける。
# トークンは output で返し、modules/aws が SSM に入れる。

locals {
  access_enabled = var.access_allowed_email != null
}

resource "cloudflare_zero_trust_access_policy" "owner" {
  count = local.access_enabled ? 1 : 0

  account_id = var.account_id
  name       = "${local.name}-owner"
  decision   = "allow"

  include = [{
    email = { email = var.access_allowed_email }
  }]
}

resource "cloudflare_zero_trust_access_service_token" "e2e" {
  count = local.access_enabled ? 1 : 0

  account_id = var.account_id
  name       = "${local.name}-e2e"
  # 既定の 1 年。切れたら client_secret_version を上げて作り直す
  duration = "8760h"
}

# サービストークンは人ではないので non_identity (ログイン画面を出さずにヘッダだけで通す)
resource "cloudflare_zero_trust_access_policy" "e2e" {
  count = local.access_enabled ? 1 : 0

  account_id = var.account_id
  name       = "${local.name}-e2e"
  decision   = "non_identity"

  include = [{
    service_token = { token_id = cloudflare_zero_trust_access_service_token.e2e[0].id }
  }]
}

resource "cloudflare_zero_trust_access_application" "site" {
  count = local.access_enabled ? 1 : 0

  account_id       = var.account_id
  name             = local.name
  type             = "self_hosted"
  domain           = var.hostname
  session_duration = "24h"

  policies = [
    {
      id         = cloudflare_zero_trust_access_policy.owner[0].id
      precedence = 1
    },
    {
      id         = cloudflare_zero_trust_access_policy.e2e[0].id
      precedence = 2
    },
  ]
}
