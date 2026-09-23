# 開発用 compose / E2E 用 compose / 短縮 URL の復元機能 — 作業計画

2026-09-23 に合意した計画。devcontainer の Rebuild で Claude Code のセッションが消えるので、ここに残す。
進めたら「進捗」のチェックを更新する。

## 背景

要件 (README の「サービス要件」) のうち、未達だったのは次の 2 つ。

- 短いURLのドメインは `https://example.com/` とする
  - サーバは `shortener.base-url` (`SHORTENER_BASE_URL` で上書き) で受け取れるが、値を渡している場所がなかった。
- 短いURLを元URLに戻す機能を実装する
  - サーバにあるのは `GET /:code` の 302 リダイレクトだけ。短縮 URL から元 URL を調べる API と画面がない。

## 決定事項

### 開発環境

- 環境変数は root の `.env` で管理する。`.env.example` はコミットし、`.env` は `.gitignore` に入れる。
  - 中身は `SHORTENER_BASE_URL=https://example.com/`。compose には `env_file: .env` で渡す。
- root に開発用の `compose.yaml` を置き、`make up` で起動する。
  - Dockerfile は `src/server/Dockerfile` と `src/front/Dockerfile` に置く。
- コンテナの中に入って作業することは想定しない。ただしホットリロードは必要。
  - server は `sbt run`、front は `bun run dev` で動かす。
  - E2E を front に向けて通れば、ホットリロードの確認も済んだとみなす。ファイルを変更して監視するような確認はしない。
- 短縮 URL は `https://example.com/xxxxxxxx` を返す。仕様どおりなので、ローカルでは開けなくてよい。
- devcontainer はホストの Docker を使う (docker-outside-of-docker) ので、バインドマウントのパスはホスト側で解釈される。
  - `.devcontainer/devcontainer.json` に `"remoteEnv": {"LOCAL_WORKSPACE_FOLDER": "${localWorkspaceFolder}"}` を足す。
  - compose のマウント元は `${LOCAL_WORKSPACE_FOLDER:-.}/src/server` の形にする。ホストから打つと `.` になる。
  - `env_file` とビルドコンテキストは compose を打った側が読むので、ホストのパスは不要。
  - Makefile で「変数が空なら止める」確認は入れない (ユーザー判断)。
- `.devcontainer/devcontainer.json` の `forwardPorts: [9000, 5173]` は外す。compose が公開するポートと衝突するため。

### E2E

- E2E は専用の compose で、開発用とは別に起動する。
  - `docker compose -p short-link-e2e -f compose.yaml -f compose.e2e.yaml` のように開発用の定義に重ね、プロジェクト名を分ける。
  - `compose.e2e.yaml` で行うこと:
    - runn のサービスを足し、`http://front:5173` にシナリオを流す
    - ポートの公開を外す
    - `SHORTENER_BASE_URL=https://example.com` と `E2E_SELF_URL=https://example.com/abcd1234` を固定で渡す
  - `make e2e` は起動 → runn の終了を待つ → `down` まで行う。sbt のキャッシュはボリュームを共有して、毎回依存を落とさないようにする。
- Vite の `allowedHosts` に `front` を足す。runn は `Host: front:5173` で来るため。
  - Vite のプロキシは `changeOrigin` なしのままにする。Play の AllowedHostsFilter に `Host: localhost:5173` / `front:5173` が届く点は、実装時に確認する。
- `tests/scenarios/health.yml` は、front 経由だと `GET /` が `index.html` になって落ちる。
  - サーバに `GET /api/v1/health` を足し、`health.yml` をそちらに向ける。
- `tests/e2e.ts` はローカルで `sbt run` を起動する役目がなくなる。compose に置き換えて削除するか、compose を操作するスクリプトに書き換えるかは、実装時に簡単な方を選ぶ。

### 短縮 URL の復元

- フロント: 既存の短縮フォームの下に、短縮 URL の入力フォームを置く。その下に元のリンクを表示する。
- API: `GET /api/v1/links/resolve?shortUrl=https://example.com/Xk3pR8vN`

  | ステータス | 内容 |
  | --- | --- |
  | 200 | `{code, shortUrl, originalUrl}` (作成 API と同じ形) |
  | 400 | `invalid_request` (`shortUrl` がない) |
  | 400 | `not_short_url` (自サービスの短縮 URL の形ではない) |
  | 404 | `not_found` (形は正しいが未登録) |

- 短縮 URL かどうかの判定はサーバに置く。公開 URL を知っているのはサーバだけなので。
  - `PublicBaseUrl.codeOf(raw): Option[String]` を足す。ホストが公開 URL と一致し、パスが `/[A-Za-z0-9]{8}` のものだけコードを返す。
- usecase: `ResolveShortLink` に短縮 URL の文字列から引く操作を足す。
  - 戻り値は `Either[ResolveShortLinkError, ShortLink]`。エラーは `NotShortUrl` / `NotFound`。
  - 既存の `GET /:code` のリダイレクトは残す。
- テスト:
  - Scala: `PublicBaseUrlSpec` / `ResolveShortLinkSpec` / `LinkControllerSpec`
  - E2E: `tests/scenarios/resolve_link.yml` に正常系・404・`not_short_url` を足す

## 進捗

1. 開発環境
   - [x] `.env.example` / `.env` / `.gitignore`
   - [x] `src/server/Dockerfile` (JDK 21 + sbt 1.13.0、uid 1000、`CMD sbt run`)
   - [x] `src/front/Dockerfile` (bun、`bun install`、`CMD bun run dev`)
   - [x] `compose.yaml`
     - server: `target/` と `project/target/` を名前付きボリュームで分ける (devcontainer の sbt / Metals と取り合わないように)
     - server: sbt / coursier のキャッシュもボリュームにする
     - server: `stdin_open: true` と `tty: true` を付ける (`sbt run` は標準入力が閉じると止まるため)
     - front: `node_modules` はボリュームで分ける。`API_ORIGIN=http://server:9000` を渡す
   - [x] `.devcontainer/devcontainer.json` (`remoteEnv` を足す、`forwardPorts` を外す)
   - [x] root の `Makefile` (`up` / `down` / `logs`)
   - [x] `docker compose config` と `docker compose build` が通ることを確認 (起動はまだ)
     - イメージ内の uid は 1000、java のパスは devcontainer と同じ `/usr/lib/jvm/java-21-openjdk-amd64`
     - `.dockerignore` を server (全除外) と front (package.json と bun.lock だけ) に置いた
   - [x] devcontainer を Rebuild / Reopen する (ユーザーが実施)
     - `LOCAL_WORKSPACE_FOLDER` にホストのパスが入っていることを確認済み
     - セッションは `claude -r` で復帰できた
2. E2E 環境
   - [x] `compose.e2e.yaml` / `make e2e`
     - `docker compose -p short-link-e2e -f compose.yaml -f compose.e2e.yaml run --rm runn` で流し、`rm -fsv` と `down` で片付ける
     - front の healthcheck は Vite 経由で `/api/v1/health` を叩く。これが通れば server も起動済み
     - sbt / coursier のキャッシュは開発用のボリュームを `name:` で共有する。`target` は別
   - [x] `GET /api/v1/health` を足し、`health.yml` を向け直す
   - [x] `E2E_SELF_URL` を合わせる (`compose.e2e.yaml` で `https://example.com/abcd1234`)
   - [x] Vite の `allowedHosts` に `front` を足す
   - [x] `tests/e2e.ts` は削除し、`tests/package.json` の `e2e` スクリプトも外した
   - [x] `make up` → `make e2e` で、今のシナリオが通ることを確認した (4 シナリオ・失敗 0)
   - 実装して分かったこと
     - Vite のプロキシは Host をプロキシ先 (`server:9000`) に書き換える。
       Play の AllowedHostsFilter に弾かれるので、`application.conf` に
       `play.filters.hosts.allowed += ${?PLAY_EXTRA_ALLOWED_HOST}` を足し、`compose.yaml` で `server` を渡した
     - `make e2e` で、開発用のキャッシュボリュームを別プロジェクトから使うと compose が warning を出す。動作に影響はない
3. 短縮 URL の復元
   - [x] サーバ (domain / usecase / controller / routes / Scala テスト)
     - `Url.path`、`PublicBaseUrl.codeOf`、`ResolveShortLink.fromShortUrl`、`LinkController.resolve` を追加 (79 テスト通過)
     - 起動中の開発用 compose で 200 / 404 / 400 `not_short_url` / 400 `invalid_request` を確認。Play のホットリロード (RELOAD) も確認
   - [x] E2E シナリオ (`resolve_link.yml` に 4 ステップ追加。`runn list` の構文チェックは通過)
   - [x] `make e2e` で通ることを確認した (4 シナリオ・16 ステップ・失敗 0)
   - [x] フロント (`api.ts` に `resolveShortUrl`、`ResolveForm.tsx`、エラー文言)
     - `bun run typecheck` と prettier は通過。ブラウザでの見た目は未確認
4. ドキュメント
   - [x] ルートの `README.md` (構成、`make up` / `make e2e`、`.env`) を書いた
   - [x] `src/server/CLAUDE.md` (make の説明、復元 API、`PLAY_EXTRA_ALLOWED_HOST`)
   - [x] `src/front/README.md` (compose での起動、画面の 2 つのフォーム)
   - [x] `tests/README.md` / `tests/CLAUDE.md` (`e2e.ts` の削除、compose での E2E)
   - あわせて `make e2e` が失敗したときに、片付ける前に server / front のログ末尾を出すようにした

## 守ること

- `/app/CLAUDE.md`: ユーザーの OK なしに実装・編集しない。サーバも勝手に起動しない。
  - この計画自体は OK 済み。ただし `make up` や `make e2e` を打つとサーバが起動するので、実行前にひと声かける。
- コメント・ドキュメントは日本語。コメントには「なぜそうするか」を書く。
