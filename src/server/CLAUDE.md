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

E2E はリポジトリルート (`/app`) から。runn の YAML シナリオを `tests/scenarios/` に置き、サーバの起動・停止は `tests/e2e.ts` (bun) が担う。

```sh
make e2e             # 未起動なら sbt run を立ち上げてシナリオを流し、終わったら止める (起動済みなら流用し止めない)
make e2e-scenarios   # 起動済みのサーバに流すだけ
make e2e E2E_ARGS=--debug
```

E2E のサーバログは `logs/e2e-server.log`。詳細は `/app/tests/README.md`。ドメインのルールや複雑なアサーションは E2E ではなく Scala 側 (`test/`) に書く方針。

## アーキテクチャ

レイヤ分けされたクリーンアーキテクチャ風の構成。依存は外側 → 内側 (`domain`) の一方向。

- `domain/` — Play に依存しない中核。
  - `Url` は検証済み URL の値オブジェクト。コンストラクタは private で `Url.from(raw): Either[Url.Error, Url]` 経由でのみ生成する。okhttp の `HttpUrl` でパース・正規化 (punycode 化、ホスト小文字化) し、http/https 以外のスキーム・認証情報付き URL・2048 文字超を拒否する。
  - `ShortLinkRepository` / `ShortLinkService` は trait。実装は外側に置く。`saveIfAbsent` はコード重複 (`CodeTaken`) と URL 登録済み (`UrlExists`) を判定と保存を不可分にして返す。
  - `PublicBaseUrl` は利用者に見せる自サービスの公開 URL (スキーム + ホスト [+ ポート])。短縮 URL は Host ヘッダではなく必ずこれから組み立てる (CloudFront 越しだと Host が origin 側になり、偽装もできるため)。
  - `ServiceHost` は自サービスのホスト名で、`PublicBaseUrl` から導く。自己参照 URL (リダイレクトループ) の拒否に使う。
- `usecase/` — 業務操作。`CreateShortLink.execute(rawUrl)` は生文字列を受け取り VO 変換まで内側で行い、`Future[Either[CreateShortLinkError, ShortLink]]` を返す。エラーは enum で表現し、例外は使わない。
  - 同じ URL には同じリンクを返す。`findByUrl` で登録済みなら採番しない。未登録なら採番 → `saveIfAbsent` し、コードが被ったら最大 10 回まで採番し直す (超えたら `CodeExhausted`)。
  - `ResolveShortLink` はコードからリンクを引くだけ。
- `service/DefaultShortLinkService` — `SecureRandom` で英数 8 文字のコードを採番。
- `infra/inmemory/InMemoryShortLinkRepository` — コード→リンク、URL→リンクの 2 つの `TrieMap` による実装 (再起動で消える)。書き込みだけ `synchronized`。
- `controllers/` — HTTP の関心事だけ。JSON の形は `Reads` で検証し (`invalid_request`)、業務エラーは usecase の enum を `error` コード (400 `invalid_url` / `self_reference`、500 `code_generation_failed`) にマップする。
- `app/Module.scala` — Guice のバインディングを集約 (trait → 実装)。`shortener.base-url` 設定 (`conf/application.conf`、環境変数 `SHORTENER_BASE_URL` で上書き可。既定はフロントの Vite `http://localhost:5173`) を検証して `PublicBaseUrl` / `ServiceHost` として `@Provides` し、usecase が Play の `Configuration` に依存しないようにしている。

ルーティングは `conf/routes`。`GET /` はヘルスチェック (E2E の起動待ちに使う)、`POST /api/v1/links` が短縮リンク作成 (Cookie セッションを持たないので `nocsrf`)、末尾の `GET /:code` が 302 で元URLへリダイレクト (未知なら 404 `not_found`)。Play の `Redirect` は既定が 303 なので `FOUND` を明示している。

usecase のテストは DI コンテナを使わず `new` で組み立て、`ShortLinkService` をスタブしてコードを固定する (`test/usecase/CreateShortLinkSpec.scala`)。

## 運用の前提

リンクはインメモリなので、常に 1 プロセスで動かす前提。複数台に振り分けると、作ったリンクが別の台で 404 になり、同じ URL に別コードが返る。本番は ALB なしの ECS on EC2 1 台 (デプロイも新旧を並べない)。スケールするときは `ShortLinkRepository` を共有ストア (DynamoDB など) の実装に差し替える。

## 規約

- コメント・ドキュメント・エラーメッセージは日本語。コメントは「なぜそうするか」を書く。
- `.g8/` は Play の giter8 scaffold テンプレートで、アプリのコードではない。
