# short-link-service

URL を短縮し、短縮 URL から元の URL に戻せるサービス。

| ディレクトリ | 中身 |
| --- | --- |
| `src/server/` | API サーバ (Scala 3 + Play Framework 3)。詳細は `src/server/CLAUDE.md` |
| `src/front/` | 画面 (Vite + React + TypeScript、bun)。詳細は `src/front/README.md` |
| `tests/` | E2E シナリオ (runn)。詳細は `tests/README.md` |

## 開発環境

root の `compose.yaml` で server (`sbt run`) と front (`bun run dev`) を起動する。
ソースはマウントするので、編集はコンテナの外で行い、ホットリロードで反映される。

```sh
cp .env.example .env   # 初回だけ
make up                # 起動 (http://localhost:5173 が画面、http://localhost:9000 が Play)
make logs              # ログを追う
make logs-server       # server のログ (JSON) を jq で整形して追う
make down              # 停止
```

- `.env` は compose が server に渡す。`SHORTENER_BASE_URL` が短縮 URL のドメインで、要件どおり `https://example.com/` にしている。そのため返ってくる短縮 URL はローカルでは開けない。
- server の初回起動は sbt が依存の取得とコンパイルを行うので、数分かかる。
- devcontainer の中から `make up` しても動く。devcontainer はホストの Docker を使うので、`.devcontainer/devcontainer.json` の `remoteEnv` でホスト側のパス (`LOCAL_WORKSPACE_FOLDER`) を渡し、`compose.yaml` のマウント元に使っている。

## E2E

```sh
make e2e                     # E2E 専用の compose を立ち上げてシナリオを流し、終わったら片付ける
make e2e E2E_ARGS=--debug    # runn に引数を足す
```

`compose.e2e.yaml` を `compose.yaml` に重ね、別プロジェクト (`short-link-e2e`) として起動する。開発用の `make up` と同時に動かしても干渉しない。
