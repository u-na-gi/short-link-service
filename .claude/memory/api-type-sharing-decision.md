---
name: api-type-sharing-decision
description: Background on deciding not to introduce API type sharing (OpenAPI/protobuf/JSON Schema) between front/server
metadata:
  node_type: memory
  type: project
  originSessionId: 2ce44372-e78b-431a-9827-8b2fd6719794
  modified: 2026-09-23T20:53:25.644Z
---

On 2026-09-23, sharing request/response types between front (React+TS) and server (Play/Scala 3) was considered, but decided against.

- The user explicitly opposed OpenAPI. If candidates were considered, they would be protobuf or JSON Schema.
- Investigation results: ScalaPB 0.11.x + scalapb-playjson 0.18.0 (supporting Scala 3 / play-json 3.0, though releases slowed down after 2024-06); on TS, @bufbuild/protobuf allowed JSON over HTTP operation.
- Even so, it was judged overkill for the number of endpoints and discarded.

**Why:** Because of the small scale, the cost of the mechanism is not justified.
**How to apply:** Do not propose a type-sharing mechanism again. If the topic resurfaces, do not bring up OpenAPI; start from the investigation results above.
