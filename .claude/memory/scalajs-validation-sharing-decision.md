---
name: scalajs-validation-sharing-decision
description: Background on deciding not to share URL validation between front/server via Scala.js, opting to implement it natively in TS on the front
metadata:
  type: project
---

On 2026-09-23, sharing server `Url.from` validation with front via Scala.js (crossProject) was considered, and decided against. Validation on the front will be implemented normally in TS.

- The core of `Url.from` (host validity, credential detection, punycode normalization) depends on okhttp `HttpUrl` and is JVM-only. The JS side would have to substitute WHATWG `URL`, so parsers cannot be shared and the advantage of sharing is small.
- Other costs: Scala.js does not generate .d.ts, sbt root move / JDK and sbt required in front's Docker, bundle size increase.
- User policy: Server is authoritative. However, clearly invalid URLs must be prevented from being submitted on the front.

**Why:** Only outer rules can be shared, which does not justify build/dev environment costs. Same judgment as [[api-type-sharing-decision]].
**How to apply:** Do not propose Scala.js sharing again. Write front validation in TS as "equal to or looser than server" (empty, length, unparseable by `URL`, non-http/https, containing credentials). Leave borderline cases to the server.
