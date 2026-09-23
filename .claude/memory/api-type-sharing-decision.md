---
name: api-type-sharing-decision
description: front/server 間の API 型共有 (OpenAPI/protobuf/JSON Schema) は導入しないと決めた経緯
metadata:
  node_type: memory
  type: project
  originSessionId: 2ce44372-e78b-431a-9827-8b2fd6719794
  modified: 2026-09-23T20:53:25.644Z
---

2026-09-23、front (React+TS) と server (Play/Scala 3) の request/response 型共有を検討したが、取り組まないと決定。

- OpenAPI は user が明確に反対。候補にするなら protobuf か JSON Schema。
- 調査結果: ScalaPB 0.11.x + scalapb-playjson 0.18.0 (Scala 3 / play-json 3.0 対応、ただしリリースは 2024-06 で止まり気味)、TS は @bufbuild/protobuf で JSON over HTTP 運用は可能だった。
- それでもエンドポイント数に対して過剰と判断して見送り。

**Why:** 小規模なので仕組みのコストが見合わない。
**How to apply:** 型共有の仕組みを再提案しない。話が再燃したら OpenAPI は出さず、上の調査結果から始める。
