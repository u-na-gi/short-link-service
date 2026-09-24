variable "env" {
  description = "環境名 (develop / staging / prod)。リソース名の接頭辞に使う"
  type        = string
}

variable "account_id" {
  description = "Cloudflare のアカウント ID。公開リポジトリに載せないため、TF_VAR_cloudflare_account_id で渡す"
  type        = string
  sensitive   = true
}

variable "hostname" {
  description = "Worker を公開するホスト名 (u-na-gi.com のサブドメイン)。Cloudflare Access をかける対象"
  type        = string
}

variable "access_allowed_email" {
  description = "Cloudflare Access でログインを許可するメールアドレス。null なら Access をかけない (prod)。公開リポジトリに載せないため TF_VAR で渡す"
  type        = string
  default     = null
  sensitive   = true
}

variable "turnstile_enabled" {
  description = "短縮と復元に Turnstile をかけるか (staging / prod は true、develop は false)"
  type        = bool
  default     = false
}
