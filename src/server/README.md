# バックエンド API サーバ (`src/server`)

URL 短縮および復元を提供する JSON API サーバです。Scala 3 と Play Framework 3 で構築されており、Twirl や静的アセット管理を排した純粋な API サーバとして動作します。

## 目次

- [技術スタック](#技術スタック)
- [開発コマンド](#開発コマンド)
- [HTTP API 仕様](#http-api-仕様)
  - [エンドポイント一覧](#エンドポイント一覧)
  - [エラーレスポンス規約](#エラーレスポンス規約)
  - [エラーコード一覧](#エラーコード一覧)
  - [リクエスト追跡 (`X-Request-Id`)](#リクエスト追跡-x-request-id)
- [アーキテクチャとレイヤ構成](#アーキテクチャとレイヤ構成)
- [コアドメインとビジネスロジック](#コアドメインとビジネスロジック)
- [ログ設計](#ログ設計)
- [テスト方針](#テスト方針)

---

## 技術スタック

| 分類                          | 採用技術                           | バージョン / 補足                   |
| ----------------------------- | ---------------------------------- | ----------------------------------- |
| 言語                          | Scala                              | 3.3.6                               |
| Web フレームワーク            | Play Framework                     | 3.0.11 (JSON API 専用)              |
| ビルドツール                  | sbt                                | 1.13.0                              |
| ランタイム                    | JDK                                | 21 (Eclipse Temurin)                |
| DI コンテナ                   | Guice                              | Play 組み込み                       |
| JSON ライブラリ               | Play JSON                          | `Reads` / `Writes`                  |
| HTTP クライアント (URL検証用) | okhttp                             | 4.12.0 (`HttpUrl` による厳格パース) |
| ロギング                      | Logback + logstash-logback-encoder | 9.0 (JSON 構造化ログ)               |
| テストフレームワーク          | ScalaTest + scalatestplus-play     | 7.0.2                               |

---

## 開発コマンド

本ディレクトリ (`src/server/`) で実行します。

```sh
# 開発サーバの起動 (http://localhost:9000 で待ち受け)
sbt run

# 全テストの実行 (現在 79 件)
sbt test

# 特定のテストクラスのみ実行
sbt "testOnly domain.UrlSpec"

# テスト名で絞り込んで実行 (ScalaTest)
sbt "testOnly domain.UrlSpec -- -z \"normalize\""

# コードフォーマット (設定: .scalafmt.conf)
sbt scalafmtAll
```

> [!NOTE]
> フロントエンドや E2E と組み合わせた全体起動は、リポジトリルートの `make up` または `make e2e` を使用します。詳細は [docs/development.md](../../docs/development.md) を参照してください。

---

## HTTP API 仕様

Play Framework はポート `9000` で待ち受けます。
ローカル開発環境では Vite 開発サーバ (`localhost:5173`) が `/api/*` および `/{英数8文字}` を Play にプロキシするため、ブラウザからは `localhost:5173` の単一オリジンとしてアクセスできます。

### エンドポイント一覧

| メソッドとパス                           | 役割                                                                                                                                         | 成功時レスポンス                                                                  | 主なエラー                                                                                         |
| ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `GET /`                                  | ヘルスチェック (API サーバ直接)                                                                                                              | 200 `{"status":"ok"}`                                                             | -                                                                                                  |
| `GET /api/v1/health`                     | ヘルスチェック (フロント / プロキシ経由用)                                                                                                   | 200 `{"status":"ok"}`                                                             | -                                                                                                  |
| `POST /api/v1/links`                     | 短縮リンク作成。Body は `{"url": "..."}`。Cookie セッションを持たないため CSRF チェック対象外 (`+ nocsrf`)。                                 | 201 `{ "code": "...", "shortUrl": "...", "originalUrl": "..." }`                  | 400 `invalid_request`<br>400 `invalid_url`<br>400 `self_reference`<br>500 `code_generation_failed` |
| `GET /api/v1/links/resolve?shortUrl=...` | 短縮 URL の復元。短縮 URL 全体をクエリパラメータで受け取り元 URL を返す。自サービスの URL かどうか、どこがコードかの判定はサーバが行います。 | 200 `{ "code": "...", "shortUrl": "...", "originalUrl": "..." }` (作成と同じ形式) | 400 `invalid_request`<br>400 `not_short_url`<br>404 `not_found`                                    |
| `GET /:code`                             | 短縮 URL のリダイレクト。ルーティング定義の末尾に配置。                                                                                      | 302 `Location: <元URL>` (Play 既定の 303 ではなく 302 を明示)                     | 404 `{"error":"not_found"}`                                                                        |

### エラーレスポンス規約

エラー時のレスポンスボディは、セキュリティ（情報漏洩防止）とシンプルさを重視し、原則として `{"error": "<エラーコード>"}` のみを返します。

- 入力値、内部の上限値、例外メッセージやスタックトレースはレスポンスに含めません。
- `invalid_url` の場合のみ、フロントエンド側で利用者に適切な案内ができるよう `reason` を付与します。
- 利用者向けの日本語メッセージはフロントエンド側 (`src/front/src/api.ts`) でエラーコードから組み立てます。

### エラーコード一覧

| ステータス | `error`                                                                          | `reason` (付与時のみ)                                                         | 発生条件                                                                                                |
| ---------- | -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| 400        | `invalid_request`                                                                | -                                                                             | JSON のパース失敗、必須項目 `url` の欠落や非文字列、`shortUrl` クエリの欠落、Play 自身の 400 エラー     |
| 400        | `invalid_url`                                                                    | `empty`<br>`too_long`<br>`unsupported_scheme`<br>`malformed`<br>`credentials` | URL の形式が不正。詳細は後述の [コアドメインとビジネスロジック](#コアドメインとビジネスロジック) を参照 |
| 400        | `self_reference`                                                                 | -                                                                             | 自サービス自身の短縮 URL を短縮しようとした（リダイレクトループ防止）                                   |
| 400        | `not_short_url`                                                                  | -                                                                             | `resolve` に渡された URL が自サービスの短縮 URL 形式（ドメイン・パス）と一致しない                      |
| 404        | `not_found`                                                                      | -                                                                             | 指定されたコードまたは短縮 URL が未発行（または再起動で消失済み）                                       |
| 500        | `code_generation_failed`                                                         | -                                                                             | ランダムコード採番の衝突リトライが上限（10回）に達した                                                  |
| 500        | `internal_error`                                                                 | -                                                                             | サーバ内の未処理例外（エラーハンドラが捕捉）                                                            |
| その他     | `forbidden`<br>`payload_too_large`<br>`unsupported_media_type`<br>`client_error` | -                                                                             | Play Framework 組み込みのエラーを `ErrorHandler` が JSON 形式に変換したもの                             |

### リクエスト追跡 (`X-Request-Id`)

- すべての HTTP レスポンスに `X-Request-Id` ヘッダ（サーバが発行した UUID）が付与されます。
- クライアントから送信された `X-Request-Id` は偽装防止のため採用せず、サーバ側で常に新規採番します。
- Play 組み込みの `request.id` (再起動で 1 に戻る連番) も使用しません。

---

## アーキテクチャとレイヤ構成

依存関係が一方向（外側 → 内側の `domain`）に向かうクリーンアーキテクチャ風のレイヤ構成を採用しています。

```
src/server/app/
├── domain/                  # Play に依存しない中核ドメイン
│   ├── Url.scala            # 検証・正規化済み URL の値オブジェクト
│   ├── ShortLink.scala      # 短縮リンクモデル (code, url)
│   ├── ShortLinkRepository.scala # リポジトリインターフェース (trait)
│   ├── ShortLinkService.scala    # コード採番・発行サービスインターフェース (trait)
│   ├── PublicBaseUrl.scala  # 自サービスの公開ベース URL 値オブジェクト
│   └── ServiceHost.scala    # 自サービスのホスト名値オブジェクト (自己参照判定用)
├── usecase/                 # アプリケーションの業務操作 (例外を使わず Either で返却)
│   ├── CreateShortLink.scala  # 短縮リンク作成ユースケース
│   └── ResolveShortLink.scala # 短縮リンク復元・検索ユースケース
├── service/                 # ドメインサービスの具象実装
│   └── DefaultShortLinkService.scala # SecureRandom による 8 文字採番とリトライ制御
├── infra/inmemory/          # インフラストラクチャ層 (インメモリ実装)
│   └── InMemoryShortLinkRepository.scala # TrieMap によるスレッドセーフなオンメモリ保持
├── controllers/             # HTTP アダプタ層
│   ├── LinkController.scala # 短縮作成・復元・リダイレクトのアクション
│   ├── HomeController.scala # ヘルスチェック
│   └── ErrorHandler.scala   # Play 自身のエラーも JSON に変換するハンドラ
├── logging/                 # 構造化ログとマスキング処理
│   ├── RequestIdFilter.scala # リクエストごとの UUID 付与
│   ├── AccessLogFilter.scala # 1 リクエスト 1 行の JSON アクセスログ
│   └── LogMasking.scala     # クエリや Body の秘匿値マスキング
└── Module.scala             # Guice DI 設定と起動時コンフィグ検証
```

### 例外を排したフロー制御

- ドメイン層およびユースケース層では業務例外を `throw` しません。
- すべて `Future[Either[ErrorEnum, Result]]` の型で結果を返し、コントローラー層で HTTP ステータスコードと JSON にマッピングします。

---

## コアドメインとビジネスロジック

### 1. URL の検証と正規化 (`domain.Url`)

`Url` のコンストラクタは `private` であり、ファクトリメソッド `Url.from(raw): Either[Url.Error, Url]` 経由でのみインスタンス化できます。

1. **空白除去**: 前後の空白をトリム。空なら `Url.Error.Empty`。
2. **文字数制限**: トリム後が 2,048 文字を超える場合は `Url.Error.TooLong`。
3. **スキーム検証**: 正規表現でスキームを抽出し、`http` または `https` のみ許可。それ以外は `Url.Error.UnsupportedScheme`（`javascript:` や `data:` などの不正スキームを排除）。
4. **構文解析**: okhttp の `HttpUrl.parse` を用いて厳格にパース。パース不能やホストが存在しない場合は `Url.Error.Malformed`。
5. **認証情報の禁止**: `user:pass@host` 形式のユーザー情報が含まれる場合はフィッシング防止のため `Url.Error.ContainsCredentials`。
6. **正規化 (Normalization)**: ホスト名の小文字化、国際化ドメイン名 (IDN) の Punycode 変換を実施。正規化後の文字列を保持し、再文字数チェックを行います。
   - 表記ゆれのある同一 URL（大文字混在や Punycode 未変換）は、すべて同一の正規化 URL に収束します。

### 2. コード採番と衝突解決 (`DefaultShortLinkService`)

- **採番形式**: 英数字 (`[a-zA-Z0-9]`) 8 文字。`SecureRandom` を用いてランダム生成。
- **既存 URL の再利用**: 採番前に `ShortLinkRepository.findByUrl` を確認し、すでに登録済みであればそのリンクを返します。
- **衝突リトライ**: 採番したコードがすでに別の URL で使われていた場合、最大 10 回まで再採番を試行します。10 回連続で衝突した場合は `CodeExhausted` (500 `code_generation_failed`) を返します。
- **並行性の安全性**: `InMemoryShortLinkRepository.saveIfAbsent` は排他制御 (`synchronized`) されており、同一 URL に対する並行リクエストが発生した場合でも重複登録や競合を防ぎます。

### 3. 公開 URL と自己参照防止 (`PublicBaseUrl`)

- **Host ヘッダ非依存**: 短縮 URL はリクエストの `Host` ヘッダではなく、設定値 `shortener.base-url` から組み立てます（リバースプロキシ配下でのホスト漏洩や偽装を防止）。
- **自己参照の拒否 (`self_reference`)**: 自サービスのホスト名 (`ServiceHost`) 宛の URL を短縮しようとした場合、無限リダイレクトループを防ぐため 400 エラーとします。
- **復元判定 (`codeOf`)**: `resolve` API では、URL のホストが自サービスと一致し、パスが `/[A-Za-z0-9]{8}` の 1 階層である場合のみコードとして抽出します。

---

## ログ設計

開発環境・本番環境を問わず、標準出力に **1 リクエスト 1 行の JSON 形式** でログを出力します (`logstash-logback-encoder`)。テキスト形式のログ出力は行いません。

### アクセスログ (`access` ロガー)

`AccessLogFilter` により、各リクエスト終了時に以下の JSON を 1 行で出力します。

- `requestId`: サーバが採番した UUID (`X-Request-Id`)
- `method`, `path`, `status`, `elapsedMs`
- `host`: `X-Forwarded-Host` を優先解釈
- `requestBody`, `responseBody`: マスキング処理済みデータ

### 秘匿情報のマスキング (`LogMasking`)

- 元 URL のクエリパラメータ等に機密情報やトークンが含まれうるため、リクエスト／レスポンスの Body やクエリは**原則としてキー名のみを残して値は `***` にマスク**します。
- 値の出力を許可しているのは、レスポンスの `error` コードと短縮 `code` のみです。
- さらに `conf/logback.xml` のデコレータ設定により、万が一ログメッセージ内に `url`, `shortUrl`, `originalUrl` が含まれた場合でも自動でマスクされる多重防御を施しています。

---

## テスト方針

本ディレクトリ内のテスト (`src/server/test/`) は、ドメインロジックの網羅的検証とコントローラーの入出力検証を担います。

- **モック / スタブの最小化**: ユースケースのテスト (`CreateShortLinkSpec`) は DI コンテナを使わず `new` で直接組み立てます。
- **乱数の固定化**: `test/support/SequenceCodes` を用いて、テスト実行時に生成されるコード順序を固定化し、衝突リトライや重複排除の挙動を決定論的にテストしています。
