# Turnstile (staging / prod). Checks "is this a human" on the shorten and resolve forms.
# front sends the widget token in the X-Turnstile-Token header; the Worker verifies it with siteverify before passing the request to Play.
# The site key goes into the front build (VITE_TURNSTILE_SITE_KEY); the secret goes into the Worker secret (TURNSTILE_SECRET_KEY).

resource "cloudflare_turnstile_widget" "site" {
  count = var.turnstile_enabled ? 1 : 0

  account_id = var.account_id
  name       = local.name
  domains    = [var.hostname]
  # Let most users through without interaction and show a checkbox only when suspicious
  mode = "managed"
}
