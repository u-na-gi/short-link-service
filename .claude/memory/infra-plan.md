---
name: infra-plan
description: AWS インフラ (Terraform + ecspresso、aqua + direnv) の合意済み計画と未決論点が docs/plan-infra.md にある
metadata:
  type: project
---

2026-09-24 から詰めている AWS インフラの計画 (develop だけ先に作る、手元で動かしてから CI、terragrunt なし、aqua + direnv) と未決の論点・進捗は `/app/docs/plan-infra.md` にある。

**Why:** devcontainer の Rebuild でセッションが消えるので、合意と未決論点を引き継ぐ必要がある。ユーザーは「コードだけ書いても一発で正しくならない」ので実際に AWS へ上げて確かめる方針。

**How to apply:** インフラ作業を再開するときはまずその計画を読み、「進捗」から続ける。未決論点はユーザーと話して決めてから実装する (勝手に決めない)。[[dev-env-and-resolve-plan]] と同じ運用。
