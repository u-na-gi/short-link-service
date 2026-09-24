variable "repository" {
  description = "リポジトリ名 (owner は provider の owner)"
  type        = string
}

variable "environment" {
  description = "GitHub の Environment 名。AWS の OIDC ロールはこの名前で引き受け元を絞っている (infra/terraform/shared/ci.tf)"
  type        = string
}

variable "branch_patterns" {
  description = "この Environment にデプロイできるブランチ。branch_patterns と tag_patterns がどちらも空なら制限しない (plan 用)"
  type        = list(string)
  default     = []
}

variable "tag_patterns" {
  description = "この Environment にデプロイできるタグ"
  type        = list(string)
  default     = []
}

variable "reviewer_user_ids" {
  description = "デプロイの前に承認が要る人 (GitHub のユーザー ID)。空なら承認なし"
  type        = list(number)
  default     = []
}

variable "variables" {
  description = "Environment の変数 (ログに出てよい値)"
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment のシークレット (ログでは伏せ字になる)"
  type        = map(string)
  default     = {}
  sensitive   = true
}
