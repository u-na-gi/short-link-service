# Integration testing

実際に起動したサーバに HTTP を投げる E2E シナリオ。[runn](https://github.com/k1LoW/runn) の runbook (YAML) で書く。

## Scala 側のテストとの棲み分け

|                        | 置き場所                     | 何を見るか                                                              |
| ---------------------- | ---------------------------- | ----------------------------------------------------------------------- |
| unit / controller spec | `url-shortener-server/test/` | アプリをインプロセスで起動し、ドメインのルールとルーティング・JSON の形 |
| E2E                    | ここ (`tests/`)              | 実際に listen しているサーバに対する、外から見た振る舞い                |

同じ検証を二重に持つためではなく、「本当にサーバとして起動して応答するか」を見るのがここの役目。
複雑なアサーションやランダム性の検証は Scala 側に置く。

## 実行

```sh
make e2e             # サーバの起動 → シナリオ実行 → サーバ停止 までまとめて
make e2e-scenarios   # 別ターミナルで sbt run 済みのサーバに流すだけ
```

`make e2e` の実体は bun で走らせる `e2e.ts`。既にサーバが上がっていればそれを使う (開発中の `sbt run` は落とさない)。
起動から始める場合、dev モードは最初のリクエストでコンパイルが走るので初回は数分かかる。
サーバのログは `url-shortener-server/logs/e2e-server.log`。

runn を直接叩くこともできる。

```sh
runn run "tests/scenarios/*.yml"
runn run "tests/scenarios/*.yml" --label smoke   # ラベルで絞る
runn list -l "tests/scenarios/*.yml"             # 一覧と構文チェック
```

### 出力を増やす

既定で `--verbose --debug-on-failure` を付けている。step ごとに desc と ok/fail が出て、
落ちたステップだけリクエストとレスポンスが丸ごと出る。

```sh
make e2e E2E_ARGS=--debug   # 成功したステップも HTTP のやり取りを全部出す
bun run e2e --debug         # tests/ から。runn へのオプションはそのまま渡る
bun run e2e --fail-fast --profile
```

`--capture <dir>` を渡すと、やり取りをファイルに吐き出せる。

### 環境変数

| 変数               | 既定値                           | 用途                                                         |
| ------------------ | -------------------------------- | ------------------------------------------------------------ |
| `E2E_BASE_URL`     | `http://localhost:9000`          | 向き先                                                       |
| `E2E_SELF_URL`     | `http://localhost:9000/abcd1234` | 自己参照の検証に使う URL。サーバの `shortener.host` と揃える |
| `E2E_BOOT_TIMEOUT` | `300`                            | サーバの起動を待つ秒数                                       |
| `E2E_SCENARIOS`    | `tests/scenarios/*.yml`          | 流す runbook                                                 |

## 構成

```
tests/
  e2e.ts                             サーバの起動〜停止込みで runn を回す (bun で実行)
  scenarios/
    health.yml                       GET / が ok を返す (smoke)
    create_link.yml                  POST /api/v1/links の正常系
    create_link_validation.yml       400 系 (invalid_request / invalid_url / self_reference)
```

## まだ書いていないシナリオ

サーバ側が未実装のため、以下は入れていない。実装したら足す。

- **短縮URLから元URLへの復元** — `GET /{code}` のルートがまだ無い。できたら runn の本領で、
  作成レスポンスの `code` を `bind` して次のステップで叩く形になる。

  ```yaml
  createThenResolve:
    bind:
      code: steps.create.res.body.code
  resolve:
    req:
      "/{{ code }}":
        get:
          headers:
            Accept: application/json
    test: current.res.status == 302
  ```

- **同じ URL には同じ短縮 URL** — 現状 `DefaultShortLinkService.generate` は毎回ランダムな
  コードを振るので、二回 POST するとコードが変わる。要件としては同一になるべきなので、
  実装後に「二回投げて `steps.first.res.body.code == steps.second.res.body.code`」を追加する。

## e2e.ts をいじるとき

シナリオは runn の YAML、その周りの段取り (サーバの起動・待機・停止) だけが TypeScript。
bun で直接実行するので、ビルドは要らない。

```sh
bun run e2e          # tests/ で。make e2e と同じ
bun install          # 型 (@types/bun) と prettier / oxlint を入れる
bunx tsc --noEmit    # 型チェック
bun run format       # prettier
bun run lint         # oxlint
```

## runn の導入

devcontainer の Dockerfile で `/usr/local/bin/runn` に入れている。
バージョンを上げるときは `.devcontainer/Dockerfile` の `RUNN_VERSION` を変えて Rebuild。
