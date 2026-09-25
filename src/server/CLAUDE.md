# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 概要

短縮 URL サービスの API サーバ。Scala 3.3 + Play Framework 3 (sbt)、JDK 21。Twirl / 静的アセットは削除済みで JSON API 専用。
要件 (リポジトリルートの `README.md`): 短縮 URL の生成と復元、同一 URL には同一の短縮 URL、永続化不要 (インメモリで可)、パスはランダムな 8 文字。

## コマンド

このディレクトリ (`src/server/`) で実行する。

```sh
sbt run                  # 開発サーバ (http://localhost:9000)。make dev でも同じ
sbt test                 # 全テスト。make test でも同じ
sbt "testOnly domain.UrlSpec"                    # 1 クラスだけ
sbt "testOnly domain.UrlSpec -- -z \"部分一致\""  # テスト名で絞る (ScalaTest)
sbt scalafmtAll          # フォーマット (make fmt)。設定は .scalafmt.conf (dialect scala3, maxColumn 100)
```

開発用のコンテナ (server と front) と E2E は、リポジトリルート (`/app`) の Makefile から動かす。詳細はルートの `README.md` と `/app/tests/README.md`。

```sh
make up    # compose.yaml で server (sbt run) と front (Vite) を起動。環境変数は root の .env から
make e2e   # E2E 専用の compose で起動し、runn のシナリオを front 経由で流して片付ける
```

ドメインのルールや複雑なアサーションは E2E ではなく Scala 側 (`test/`) に書く方針。

## アーキテクチャ

レイヤ分けされたクリーンアーキテクチャ風の構成。依存は外側 → 内側 (`domain`) の一方向。

- `domain/` — Play に依存しない中核。
  - `Url` は検証済み URL の値オブジェクト。コンストラクタは private で `Url.from(raw): Either[Url.Error, Url]` 経由でのみ生成する。okhttp の `HttpUrl` でパース・正規化 (punycode 化、ホスト小文字化) し、http/https 以外のスキーム・認証情報付き URL・2048 文字超を拒否する。
  - `ShortLinkRepository` / `ShortLinkService` は trait。実装は外側に置く。`ShortLinkService.issue(url)` は一意なコードを振って保存までを担う。`saveIfAbsent` はコード重複 (`CodeTaken`)、URL 登録済み (`UrlExists`)、件数の上限 (`Full`) を判定と保存を不可分にして返す。URL が登録済みなら上限に関係なく既存のリンクを返す。
  - `PublicBaseUrl` は利用者に見せる自サービスの公開 URL (スキーム + ホスト [+ ポート])。短縮 URL は Host ヘッダではなく必ずこれから組み立てる (本番は Cloudflare Worker と Tunnel 越しで Host が `localhost:9000` になり、偽装もできるため)。
  - `ServiceHost` は自サービスのホスト名で、`PublicBaseUrl` から導く。自己参照 URL (リダイレクトループ) の拒否に使う。
  - `PublicBaseUrl.codeOf(raw)` は自サービスの短縮 URL からコードを取り出す。ホスト名だけで判定し (`ServiceHost` と同じ基準)、パスは英数 8 文字の 1 階層だけ。クエリ・フラグメントは無視する。
- `usecase/` — 業務操作。`CreateShortLink.execute(rawUrl)` は生文字列を受け取り VO 変換まで内側で行い、`Future[Either[CreateShortLinkError, ShortLink]]` を返す。エラーは enum で表現し、例外は使わない。
  - 同じ URL には同じリンクを返す。`findByUrl` で登録済みなら採番しない。未登録なら `ShortLinkService.issue` に発行を任せ、その `CodeExhausted` / `StorageFull` を usecase のエラーに写す。
  - `ResolveShortLink.execute(code)` はコードからリンクを引く (リダイレクト用)。`fromShortUrl(raw)` は貼り付けられた短縮 URL を `codeOf` で解釈して引き、`NotShortUrl` / `NotFound` を返す。
- `service/DefaultShortLinkService` — `ShortLinkService` の実装。`SecureRandom` で英数 8 文字のコードを採番して `saveIfAbsent` し、コードが被ったら最大 10 回まで採番し直す (超えたら `CodeExhausted`)。採番し直しは乱数方式の都合なので usecase ではなくここに置く。テストでは主コンストラクタにコード生成関数 (`test/support/SequenceCodes`) を渡して固定する。
- `infra/inmemory/InMemoryShortLinkRepository` — コード→リンク、URL→リンクの 2 つの `TrieMap` による実装 (再起動で消える)。書き込みだけ `synchronized`。
  - 公開の書き込み API でメモリを使い切られないよう、件数に上限を持つ (`shortener.max-links`、環境変数 `SHORTENER_MAX_LINKS`、既定 10 万件)。`TrieMap.size` は O(n) なので、件数は書き込みと同じロックの中でカウンタで数える。上限は `Module` が `LinkCapacity` として検証して渡す (0 以下なら起動を止める)。テストでは主コンストラクタの `maxLinks` (既定は上限なし) を使う。
- `controllers/` — HTTP の関心事だけ。JSON の形は `Reads` で検証し (`invalid_request`)、業務エラーは usecase の enum を `error` コード (400 `invalid_url` / `self_reference` / `not_short_url`、404 `not_found`、500 `code_generation_failed`、503 `storage_full`) にマップする。作成と復元は同じ形 (`code` / `shortUrl` / `originalUrl`) を返す。
  - エラーの body は `{"error": コード}` だけ。`invalid_url` だけは `reason` (`empty` / `malformed` / `unsupported_scheme` / `credentials` / `too_long`、フロントの `UrlProblem` と同じ値) を付ける。入力値・上限値・検証の詳細・内部の事情は返さない。利用者向けの日本語の文言はフロント (`src/front/src/api.ts`) がコードから組み立てる。
  - domain / usecase のエラーは enum で返し、文言を持たせない。`PublicBaseUrl.from` のエラーも `PublicBaseUrl.Error` で、`Module` が英語のメッセージにして起動を止める (パース失敗は元の例外を cause に付ける)。
- `app/Module.scala` — Guice のバインディングを集約 (trait → 実装)。`shortener.base-url` 設定 (`conf/application.conf`、環境変数 `SHORTENER_BASE_URL` で上書き可。既定はフロントの Vite `http://localhost:5173`) を検証して `PublicBaseUrl` / `ServiceHost` として `@Provides` し、usecase が Play の `Configuration` に依存しないようにしている。

### ログ

`conf/logback.xml` で、開発でも本番でも JSON を 1 行ずつ標準出力に出す (logstash-logback-encoder)。手元では `make logs-server` で jq を通して読む。

- `logging/RequestIdFilter` — 一番外側のフィルタ。リクエストごとに UUID を振って属性 (`RequestId.Key`) に入れ、レスポンスの `X-Request-Id` で返す。Play の `request.id` は再起動で 1 から振り直す連番なので使わない。送られてきた `X-Request-Id` は偽装できるので使わない。action の例外はここで ID 付きのリクエストとして ErrorHandler に渡す (Play に任せると属性の無い元のリクエストで呼ばれ、ID が付かない)。
- `logging/AccessLogFilter` — 1 リクエスト 1 行のアクセスログ (ロガー名 `access`)。method・host (`X-Forwarded-Host` 優先)・path・status・所要時間・requestId と、body とクエリを出す。Play の既定のフィルタより外側に置き、弾かれたリクエストも残す。ヘルスチェックは DEBUG。
- body とクエリは `logging/LogMasking` でキーだけ残して値を隠す。元 URL のクエリにトークンが入りうるので、出してよいキーだけ列挙する方式 (レスポンスの `error` と `code` だけ値を出す)。URL の項目 (`url` / `shortUrl` / `originalUrl`、`AccessLogFilter.UrlKeys`) は何が送られたか追えるよう、`LogMasking.urlSummary` でスキーム・ホスト・ポート・パス (256 文字で切る)・クエリのキーに分けて出す (クエリの値・フラグメント・ユーザー情報は出さない。値の無いパラメータ `?token` は名前も隠す)。`logback.xml` の `MaskingJsonGeneratorDecorator` は、スキーム付きの URL に見える文字列を項目名によらず隠す保険。
- `controllers/ErrorHandler` — Play 自身のエラーも `{"error"}` の JSON で返す (Play のメッセージには body の断片が入りうるので DEBUG ログにだけ出す)。未処理の例外はスタックトレース付きで ERROR に出し、利用者には `internal_error` だけ返す。例外にならない 500 (`CodeExhausted`) は controller で ERROR を出す。
- アクセスログ以外のログにも `logging.RequestLog.marker(request)` で requestId を付ける。自動では付かない (Future でスレッドをまたぐので MDC は使っていない)。付け忘れるとどのリクエストのログか追えなくなる。
- root は WARN なので、自前のロガーは `logback.xml` にロガー名を足さないと INFO が出ない (今は `access` と `controllers`)。

ルーティングは `conf/routes`。`GET /` と `GET /api/v1/health` はヘルスチェック (フロント経由だと `/` は index.html になるので、E2E は後者を見る)、`POST /api/v1/links` が短縮リンク作成 (Cookie セッションを持たないので `nocsrf`)、`GET /api/v1/links/resolve?shortUrl=` が短縮 URL からの復元、末尾の `GET /:code` が 302 で元URLへリダイレクト (未知なら 404 `not_found`)。Play の `Redirect` は既定が 303 なので `FOUND` を明示している。

usecase のテストは DI コンテナを使わず `new` で組み立て、`DefaultShortLinkService` に `SequenceCodes` を渡してコードを固定する (`test/usecase/CreateShortLinkSpec.scala`)。採番し直しのテストは `test/service/DefaultShortLinkServiceSpec.scala`。

`conf/application.conf` の `play.filters.hosts.allowed` には、環境変数 `PLAY_EXTRA_ALLOWED_HOST` で許可するホストを 1 つ足せる。compose では Vite のプロキシが Host をプロキシ先 (`server:9000`) に書き換えるので、`compose.yaml` で `server` を渡している。

## 運用の前提

リンクはインメモリなので、常に 1 プロセスで動かす前提。複数台に振り分けると、作ったリンクが別の台で 404 になり、同じ URL に別コードが返る。本番は ECS on Fargate の 1 タスク (デプロイも新旧を並べない。構成はルートの `docs/production-architecture.md`)。スケールするときは `ShortLinkRepository` を共有ストア (DynamoDB など) の実装に差し替える。

## 規約

- コメント・ドキュメントは日本語。コメントは「なぜそうするか」を書く。
- サーバのログのメッセージは英語 (例外があれば stack trace ごと出す)。利用者に見せる文言は API では返さず、フロントが日本語で持つ。
- `.g8/` は Play の giter8 scaffold テンプレートで、アプリのコードではない。
