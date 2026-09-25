---
name: logging-json-decision
description: server のログは開発でも本番でも JSON (logstash-logback-encoder)、body は値を隠す方針を user が強く指定
metadata:
  type: project
---

2026-09-23、server にロギングを入れた際に user が「開発でも本番でも JSON で出す構成、ぜったいにそうして」と指定。

- dev 用に読みやすいテキスト形式へ切り替える案は出さない (マスクの保険が JSON のときしか効かないのも理由)。手元は jq で読む。
- body・クエリは「ドメイン・エンドポイント・渡されたプロパティ名」だけ出し、値は全部隠す、が user の要望。レスポンスの `error` と `code` だけ値を出す例外は合意済み。
- 2026-09-25: URL の項目はホストもパスも出してよい (隠すのはクエリの値だけ) と user が判断。`LogMasking.urlSummary` で部品に分けて出す。

**Why:** 本番 CloudWatch で項目検索したい / 元 URL にトークンが入りうる。
**How to apply:** ログ周りを触るときは JSON 前提・値は allowlist でだけ出す方針を崩さない。詳細な構成は src/server/CLAUDE.md の「ログ」節。
