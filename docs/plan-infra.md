# インフラ (AWS + Cloudflare、Terraform + ecspresso + wrangler) — 作業計画

2026-09-24 からユーザーと詰めている計画。devcontainer の Rebuild で Claude Code のセッションが消えるので、ここに残す。
進めたら「進捗」のチェックを更新し、決まった論点は「決定事項」へ移す。

## 進め方

- 2026-09-24 以降、step ごとにユーザーの確認は取らずに進めてよい (ユーザー指定)。各 step の結果はサブエージェントに検証させる。
- ただし計画にない大きな設計変更や、未決の論点を決めるときはユーザーと話す。

## 構成

```
ブラウザ → Cloudflare Worker (short-link-<env>.<サブドメイン>.workers.dev)
            ├ /api/*, /[A-Za-z0-9]{8} → Workers VPC (VPC Service) → Cloudflare Tunnel
            │                            → ECS on Fargate のタスク (cloudflared サイドカー → localhost:9000 の Play)
            └ それ以外                  → Workers の静的アセット (src/front の dist)
認証 (develop / staging): Cloudflare Access で workers.dev の URL を守る。prod は認証なし
```

### 経緯 (2026-09-24)

最初は CloudFront → 内部 NLB (VPC Origin) → ECS Managed Instances の構成で、step 0〜3 を apply した。
ところが新規アカウントの制限で、次の 3 つがすべて拒否された。

- ELB: `OperationNotPermitted: This AWS account currently does not support creating load balancers`
- CloudFront: `Your account must be verified before you can add new CloudFront resources`
- EC2: 「Running On-Demand Standard instances」の vCPU 上限が 1 (Managed Instances の 2 vCPU インスタンスが起動できない)

AWS サポートの解除を待たずに動かすため、ユーザーと相談して上の構成に切り替えた。Fargate の vCPU 上限は 6 なので使える。
元の構成 (CloudFront + NLB) に戻したくなったら、サポートに ELB / CloudFront の解除を、Service Quotas に vCPU 上限の引き上げを依頼する。

## 決定事項

- 実際にデプロイして動かす。コードを書いて validate を通すだけにはしない (動かさないと正しいか分からないため)。
- **develop が最優先**。ただし最短構成にこだわって手を抜かない (ユーザー指定)。
- 環境は develop / staging (main ブランチ) / prod (タグを切ったらリリース)。
  - **まず develop だけ作る**。動いてから modules を使い回して staging / prod を足す。
  - 最初は `terraform destroy` しやすい作りにし、壊したくないもの (state の S3 など) は `bootstrap/` に分ける。
- **手元から apply / deploy して動いてから CI (GitHub Actions + OIDC) に載せる**。
- ツールの境界:
  - Terraform: AWS (VPC / SG / ECS クラスタ / ECR / IAM / SSM / ロググループ) と Cloudflare (Tunnel / VPC Service / Access)
  - ecspresso: ECS の service と task definition だけ。ARN などは `tfstate` プラグインで Terraform の state から引く
  - wrangler: Worker (front の静的アセットと API への振り分け) のデプロイ
- 環境はワークスペースではなくディレクトリ (`infra/terraform/envs/{develop,staging,prod}`) で分ける。state は S3 backend + `use_lockfile`。
  - 環境をまたいで 1 つだけのもの (ECR) は `infra/terraform/shared/`。
- **terragrunt は使わない**。この規模では重複が backend / provider の十数行だけなので。
- **CLI の版管理は aqua、環境の切り替えは direnv**。
  - `aqua.yaml` / `aqua-checksums.json` はリポジトリの root。terraform / tflint / ecspresso / aws cli を入れる。wrangler は npm パッケージなので front の devDependencies。
  - `infra/.envrc` に `AWS_PROFILE` とリージョン。秘密 (Cloudflare の API トークンなど) は `infra/.envrc.local` (gitignore)。
- AWS アカウントは 1 つ。3 環境を同居させ、リソース名の接頭辞と tfstate の key で分ける。
  - root に MFA、IAM Identity Center (ap-northeast-1) で普段使いのユーザーと `AdministratorAccess`。
  - devcontainer からは、ホストの `~/.aws` をマウントして SSO で認証する。プロファイル名は `short-link-develop`。
  - リポジトリは最終的に public にするので、AWS / Cloudflare のアカウント ID はコミットしない。
- Cloudflare はユーザーの既存アカウントを使う。独自ドメインは使わず `*.workers.dev`。
- **サーバ (タスク) は 1 台固定**。データがインメモリなので、2 台以上になると壊れる (`desiredCount = 1`、`maximumPercent = 100`、`minimumHealthyPercent = 0`)。
- **コンピュートは ECS on Fargate** (EC2 の vCPU 上限を避けるため。ちょっとしか起動しないので費用も気にしない)。
  - タスクは public subnet に置いてパブリック IP を付け、ECR / CloudWatch Logs / SSM / Cloudflare へはそこから出る。NAT は置かない。
  - 入口は開けない (SG の ingress なし)。外からは Cloudflare Tunnel (cloudflared が外向きに張る) でだけ届く。
- **Cloudflare Tunnel は cloudflared をタスクのサイドカーにする**。awsvpc で同じネットワーク名前空間なので、VPC Service の宛先は `localhost:9000`。
  - Tunnel のトークンは Terraform で SSM SecureString に入れ、ecspresso の `secrets` で渡す。
  - Workers VPC はベータ (無料プランでも使える)。
- **Worker が front の静的アセットと API への振り分けを持つ**。`/api/*` と `/[A-Za-z0-9]{8}` を VPC Service に流し、リダイレクトは追わずにそのまま返す。
- **develop と staging は Cloudflare Access で認証する** (prod は入れない)。ログインするのはユーザー本人だけ。
  - Zero Trust のチームは `spring-wood-2a8c` (`spring-wood-2a8c.cloudflareaccess.com`)。許可するメールアドレスは `TF_VAR_access_allowed_email` (infra/.envrc.local) で渡し、リポジトリに載せない。
- **ホスト名はユーザーの Cloudflare ゾーン `u-na-gi.com` のサブドメイン** (Worker のカスタムドメイン)。
  - prod: `s.u-na-gi.com` / staging: `s-stg.u-na-gi.com` / develop: `s-dev.u-na-gi.com`
  - workers.dev の URL は使わなくなる (Access をすり抜けられないよう、カスタムドメインに移したら workers.dev は切る)。
- **公開の書き込み (短縮・復元) を守る** (ユーザー指定):
  - DDoS: Cloudflare の自動防御 (設定不要)。
  - **Turnstile** (staging / prod のみ、develop はなし): 短縮と復元のフォームに付ける。front がウィジェットのトークンをヘッダで送り、**Worker が siteverify で検証**してから Play に渡す。Play には手を入れない。ウィジェットは Terraform (`cloudflare_turnstile_widget`)、サイトキーは front のビルド時、シークレットは Worker の secret。短縮 URL のリダイレクト (`GET /xxxxxxxx`) には付けない。
  - **回数制限** (全環境): Workers の Rate Limiting で IP ごとに、短縮と復元は 1 分 20 回、リダイレクトは 1 分 100 回。
  - **アプリ側の件数上限**: 環境変数で上限を渡し、既定は 10 万件。件数は保存と同じ `synchronized` の中でカウンタを持ち O(1) で判定する (`TrieMap.size` は O(n) なので使わない)。100 万件は URL が最大 2048 文字だと数 GB になり、ヒープ (1.1GB) に入らないため既定にしない。
- **本番用 Dockerfile は開発用と別ファイル** (`src/server/Dockerfile.prod`)。
  - パッケージは Play 同梱の sbt-native-packager で `sbt stage` (sbt-pack などのプラグインは足さない)。
  - マルチステージ: JDK 21 + sbt 1.13.0 で `sbt stage` → `eclipse-temurin:21-jre` に `target/universal/stage` をコピー。
  - `-Dpidfile.path=/dev/null` で PID ファイルを書かせない (コンテナ再起動で起動できなくなるため)。
  - ECR へは `--provenance=false --sbom=false` で単一マニフェストにして push する (index だと実体がタグなしになり、ライフサイクルで消える)。
- `play.http.secret.key` は Terraform の `random_password` で作って SSM SecureString に入れ、ecspresso の `secrets` で渡す (値は tfstate にも載るので、state の S3 は暗号化とパブリックアクセスのブロックで守る)。
- リリースの流れ: PR で plan、develop / main への push で apply と deploy、タグで prod。prod は main で作ったイメージを付け直して使う (再ビルドしない)。

## 進捗

1. devcontainer にツールを入れる
   - [x] `aqua.yaml` / `aqua-checksums.json`
   - [x] `.devcontainer/Dockerfile`: aqua 本体と direnv、aqua の bin を PATH に、bashrc に direnv の hook
   - [x] `.devcontainer/compose.yaml`: `~/.aws` のマウント、aqua のキャッシュを名前付きボリュームに
   - [x] `.devcontainer/devcontainer.json`: `initializeCommand` で `~/.aws` を作る、`postCreateCommand` で `aqua i -l`
   - [x] Rebuild して `terraform version` などが動くことを確認
2. AWS の認証
   - [x] `aws configure sso` (start URL は Identity Center の access portal URL、region は ap-northeast-1)
   - [x] `aws sts get-caller-identity --profile short-link-develop` が通る
3. develop を作る (各ステップで実際に確かめてから次へ)
   - [x] 0. `infra/terraform/bootstrap/`: tfstate 用 S3 (バージョニング・暗号化・パブリックアクセスのブロック)。bootstrap 自体の state も同じバケット (`bootstrap/terraform.tfstate`)。バケットは `short-link-service-tfstate-0b102b6e`
   - [x] 1. `src/server/Dockerfile.prod`。ビルドと起動を確認済み (秘密鍵なしは起動拒否、短縮 / 復元 / 302 / 不正 Host は 400)
     - Play 3 の reference.conf には秘密鍵の環境変数がないので、`conf/application.conf` に `play.http.secret.key = ${?PLAY_HTTP_SECRET_KEY}` を足した
   - [x] 2. network: VPC、2 AZ の public / private subnet
   - [x] 3. ECR (`shared/`)、ECS クラスタ、ロググループ、SSM、タスク実行ロール / タスクロール。ECR に push できる
   - [x] 4. 構成の切り替え (AWS 側): NLB / target group / Managed Instances の capacity provider と IAM を外し、Fargate 用にする。タスクの SG は ingress なし。private subnet も消した。server だけのタスクを Fargate で起動し、パブリック IP で ECR から pull・SSM の秘密鍵・CloudWatch Logs を確認済み
   - [x] 5. Cloudflare: Tunnel、トークンを SSM へ、VPC Service (`127.0.0.1:9000`)。ユーザーから API トークンとアカウント ID をもらう
     - `modules/cloudflare/` (トークンは output で返し、`modules/aws` が SSM に入れる)。`modules/app` からの付け替えは `moved` で、作り直しなし
     - 認証情報は `infra/.envrc.local` の `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_ACCOUNT_ID` / `TF_VAR_cloudflare_account_id`
   - [x] 6. ecspresso: task def (`FARGATE`、server + cloudflared サイドカー。タスク 0.5 vCPU / 2048、server 1536 / cloudflared 256。server は curl でヘルスチェックし、cloudflared は server が HEALTHY になってから起動) と service def (1 台、100/0、public subnet + パブリック IP)。タスクが動き、Tunnel が HEALTHY になる。VPC Service の宛先 127.0.0.1 (ループバック) で実際に届くことを確認済み。Tunnel は QUIC で 4 接続
   - [x] 7. Worker: 静的アセット + `/api/*` と短縮 URL の振り分け (wrangler)。`src/front/wrangler.jsonc` の `env.develop`、URL は `https://short-link-develop.runacy58.workers.dev` (公開 URL は `envs/develop` の output `public_base_url`)。静的アセットが短縮 URL を飲み込まないよう `run_worker_first: true` にする。Tunnel に繋がらないときは 502 `server_unavailable` を返す。
     - 確認済み: 短縮 / 同じ URL は同じコード / 復元 / 302 / 未登録 404 / 自己参照・不正 URL の 400 / タスク停止中は API が 502 で画面は 200 / 1 台に戻すと数秒で復帰 / アクセスログの host は workers.dev のホスト
     - deploy 直後の 1 分ほどは、エッジによって古い状態が見えて 1042 や 404 が混じる。確認は少し待ってから`SHORTENER_BASE_URL` を workers.dev の URL にして deploy し直す。ブラウザで短縮と復元ができる
   - [x] 8. アプリの件数上限 (`SHORTENER_MAX_LINKS`、既定 10 万件、上限で 503 `storage_full`。登録済み URL は上限でも返す。本番モードで 0 以下なら起動しないことを確認)
   - [x] 8a. カスタムドメイン `s-dev.u-na-gi.com`、回数制限 (Workers Rate Limiting)
     - Worker の回数制限 (`API_LIMITER` 20/分、`REDIRECT_LIMITER` 100/分、キーは `cf-connecting-ip`、超えたら 429 `rate_limited`) と `wrangler.jsonc` (custom_domain、workers_dev: false) は書いてテスト済み。Access を作ってから deploy した (先に deploy すると認証なしで公開される)。workers.dev は 404 で閉じたことを確認
     - 件数上限入りのイメージで ecspresso の deploy 済み (task def rev 4、`SHORTENER_BASE_URL=https://s-dev.u-na-gi.com`)
     - 回数制限は実地では 429 にならなかった。リクエストが複数のデータセンター (KIX / NRT) に散り、Workers Rate Limiting はデータセンターごとの緩い数え方 (ドキュメントでも eventually consistent) のため。1 か所で 1 分 58 回でも通った。**厳密な制限としては当てにしない**。ユーザーの指示でこれ以上の検証はしない。主な守りは Cloudflare の DDoS 防御と Turnstile (staging / prod)
   - [x] 8b. Cloudflare Access (develop)。`modules/cloudflare/access.tf`。未ログインではページ / API / 短縮 URL すべてが Access のログインへ 302 になることを確認。ブラウザでのログインはユーザーが確認する未ログインならログイン画面に飛び、ログインすれば使える
     - E2E は Access のサービストークンで通す (ユーザー了承)。トークンは Terraform で作って SSM (`/short-link-<env>/e2e/access-client-{id,secret}`) へ。シナリオは全ステップの headers に `*access` (vars のアンカー)。`make e2e-remote ENV=develop`
     - `make e2e-remote ENV=develop` で 4 シナリオ 16 ステップが Access 越しに通ることを確認済み
   - [ ] 9. destroy 手順 (ECS の service を 0 にしてから terraform destroy。cloudflared が繋がったままだと Tunnel を消せない)、`docs/production-architecture.md` / `src/server/CLAUDE.md` を実際の構成に書き直す
4. staging / prod と Turnstile
   - [x] `envs/staging` (`s-stg.u-na-gi.com`、10.20.0.0/16、Access あり) と `envs/prod` (`s.u-na-gi.com`、10.30.0.0/16、Access なし) を apply (Turnstile のウィジェット以外)
   - [x] staging / prod の ECS を develop と同じイメージで deploy
   - [x] Worker の Turnstile 検証 (`worker/turnstile.ts`。短縮 / 復元だけ、action とホスト名も照合、siteverify に繋がらなければ断る、`TURNSTILE=on` なのにシークレットが無ければ断る)
   - [x] front のウィジェット (`src/turnstile.ts`。普段は見えず必要なときだけチェック、送信ごとに新しいトークン、サイトキーの無い環境では何もしない)
   - [x] `make deploy-front ENV=...` (サイトキーはビルドに、シークレットは `--secrets-file` で同じバージョンに)。null の output は state に載らないので空として扱う
   - [x] E2E: Turnstile の環境では機能のシナリオを飛ばし、`turnstile.yml` でトークン無し・偽トークンが 403 になることを見る。機能の E2E は develop で流す (Access のサービストークンで Turnstile を迂回させると穴になるので、しない)
   - [x] Turnstile のウィジェットを apply、`make deploy-front` で staging / prod に deploy、`make e2e-remote` が staging / prod で通る (Turnstile の 403 と、リダイレクトにはかからないこと)
     - Cloudflare のトークンの権限は、変えてから効くまで数分かかることがある (Access のサービストークン、Turnstile とも、直後は 403 だった)
     - 手元 (devcontainer) の DNS は Tailscale 経由らしく、作ったばかりのホスト名 (`s.u-na-gi.com`) が引けたり引けなかったりした。公開 DNS (1.1.1.1) では安定して引けたので、E2E の失敗が名前解決なら疑う
   - [ ] ブラウザでの確認 (ユーザー): staging / prod で短縮と復元ができ、Turnstile が通ること
5. リポジトリの公開 (2026-09-24、ユーザー了承済み)
   - [x] 会社や課題を特定できる記述を、履歴ごと `git filter-repo` で消した (課題文のメモ、例の URL と短縮コード)。コミットの作者は GitHub の noreply アドレスにした
   - [x] 書き換えた履歴を新しい `u-na-gi/short-link-service` (Public) に push。元のリポジトリは `u-na-gi/short-link-service-private` (Private) に改名して残してある (手元の remote 名は `private`)
   - [x] Actions: 外部の人の PR は承認してから動かす、ワークフローの既定の権限は読み取りだけ
   - これからのコミットでも、会社名・課題文・メールアドレス・アカウント ID・トークンを入れない (CI に秘密情報の検出を入れる)
6. CI (GitHub Actions + OIDC) に載せる
   - CI の環境変数・シークレットは **GitHub Environments** (develop / staging / prod) で持つ (ユーザー指定)。Environments とその変数・シークレットは Terraform の `github` モジュール (integrations/github provider) で管理する
   - `wrangler.jsonc` の VPC Service の ID とホスト名の二重管理もここで片付ける
