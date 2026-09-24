# Turnstile (staging / prod)。短縮と復元のフォームで「人間か」を確かめる。
# front がウィジェットのトークンを X-Turnstile-Token ヘッダで送り、Worker が siteverify で検証してから Play に渡す。
# サイトキーは front のビルド時 (VITE_TURNSTILE_SITE_KEY)、シークレットは Worker の secret (TURNSTILE_SECRET_KEY)。

resource "cloudflare_turnstile_widget" "site" {
  count = var.turnstile_enabled ? 1 : 0

  account_id = var.account_id
  name       = local.name
  domains    = [var.hostname]
  # 多くの利用者は操作なしで通し、怪しいときだけチェックボックスを出す
  mode = "managed"
}
