locals {
  # リソース名の接頭辞。例: short-link-develop (modules/aws と揃える)
  name = "short-link-${var.env}"
}
