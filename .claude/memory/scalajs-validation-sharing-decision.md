---
name: scalajs-validation-sharing-decision
description: URL 検証を Scala.js で front/server 共有する案は見送り、front は TS で自前実装すると決めた経緯
metadata:
  type: project
---

2026-09-23、server の `Url.from` の検証を Scala.js (crossProject) で front と共有する案を検討し、見送りと決定。front の検証は TS で普通に実装する。

- `Url.from` の中核 (ホスト妥当性・認証情報検出・punycode 正規化) は okhttp `HttpUrl` 依存で JVM 専用。JS 側は WHATWG `URL` に差し替えるしかなく、パーサが共有できないので共有の利点が薄い。
- 他のコスト: Scala.js は .d.ts を出さない、sbt ルート移動・front の Docker に JDK/sbt が要る、バンドル増。
- user の方針: server が正。ただし明らかに不正な URL は front で送信できないようにする。

**Why:** 共有できるのは外側のルールだけで、ビルド・開発環境のコストに見合わない。[[api-type-sharing-decision]] と同じ判断。
**How to apply:** Scala.js 共有を再提案しない。front の検証は「server と同じかより緩く」(空・長さ・`URL` でパース不可・http/https 以外・認証情報付き) を TS で書く。迷うケースは server に任せる。
