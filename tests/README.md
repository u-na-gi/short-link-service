# Integration testing

実際に起動したサーバに HTTP を投げる E2E シナリオ。[runn](https://github.com/k1LoW/runn) の runbook (YAML) で書く。

## Scala 側のテストとの棲み分け

|                        | 置き場所                     | 何を見るか                                                              |
| ---------------------- | ---------------------------- | ----------------------------------------------------------------------- |
| unit / controller spec | `src/server/test/` | アプリをインプロセスで起動し、ドメインのルールとルーティング・JSON の形 |
| E2E                    | ここ (`tests/`)              | 実際に listen しているサーバに対する、外から見た振る舞い                |

同じ検証を二重に持つためではなく、「本当にサーバとして起動して応答するか」を見るのがここの役目。
複雑なアサーションやランダム性の検証は Scala 側に置く。

## 実行

リポジトリルートで。

```sh
make e2e                              # E2E 専用の compose で server / front を立ち上げ、シナリオを流して片付ける
make e2e E2E_ARGS=--debug             # runn への引数を足す (成功したステップも HTTP のやり取りを全部出す)
make e2e E2E_ARGS="--label smoke"     # ラベルで絞る
runn list -l "tests/scenarios/*.yml" </dev/null   # 一覧と構文チェック (stdin を閉じないと待ち続ける)
```

`make e2e` は `compose.e2e.yaml` を `compose.yaml` に重ね、別プロジェクト (`short-link-e2e`) として起動する。

- 経路は runn → front (Vite, `http://front:5173`) → server (Play)。ブラウザと同じくプロキシ越しに検証する。
- front の healthcheck が Vite 経由で `/api/v1/health` に届いたら、シナリオを流し始める。
  - Play の dev モードは最初のリクエストでコンパイルするので、初回は数分かかる。
- server には `.env` ではなく固定値 (`SHORTENER_BASE_URL=https://example.com`) を渡す。
- 開発用の `make up` と同時に動かしても、ポート・インメモリの状態・ビルド成果物は混ざらない。sbt / coursier のキャッシュだけ共有する。
- 失敗したときは、片付ける前に server / front のログの末尾 100 行を出す。

既定で `--verbose --debug-on-failure` を付けている。step ごとに desc と ok/fail が出て、
落ちたステップだけリクエストとレスポンスが丸ごと出る。

### 環境変数

シナリオ側の変数。`compose.e2e.yaml` の runn サービスで渡している。

| 変数                     | 既定値                           | 用途                                                                   |
| ------------------------ | -------------------------------- | ---------------------------------------------------------------------- |
| `E2E_BASE_URL`           | `http://localhost:9000`          | 向き先。compose では `http://front:5173`                               |
| `E2E_SELF_URL`           | `http://localhost:9000/abcd1234` | 自己参照の検証に使う URL。ホストをサーバの `shortener.base-url` と揃える |
| `E2E_UNKNOWN_SHORT_URL`  | `https://example.com/zzzzzzzz`   | 未発行の短縮 URL。ホストをサーバの `shortener.base-url` と揃える       |

## 構成

```
tests/
  scenarios/
    health.yml                       GET /api/v1/health が ok を返す (smoke)
    create_link.yml                  POST /api/v1/links の正常系 (同じ URL なら同じ code)
    resolve_link.yml                 GET /{code} の 302 と、GET /api/v1/links/resolve による復元
    create_link_validation.yml       400 系 (invalid_request / invalid_url / self_reference)
```

## runn の導入

`make e2e` は runn の公式イメージ (`ghcr.io/k1low/runn`) を使う。版は `compose.e2e.yaml` で固定している。
devcontainer にも `/usr/local/bin/runn` として入れてあり、構文チェック (`runn list`) に使う。
版を上げるときは両方 (`compose.e2e.yaml` と `.devcontainer/Dockerfile` の `RUNN_VERSION`) を揃える。
