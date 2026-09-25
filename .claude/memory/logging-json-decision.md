---
name: logging-json-decision
description: User strongly specified that server logs must be JSON (logstash-logback-encoder) in both dev and prod, with body values masked
metadata:
  type: project
---

On 2026-09-23, when adding logging to the server, the user specified: "Output in JSON for both dev and prod, absolutely make sure of that."

- Do not propose switching to a human-readable text format for dev (another reason is that masking safeguards only work in JSON). Read locally using jq.
- The user requested that bodies and queries output only "domain, endpoint, and passed property names", masking all values. The exception of outputting values only for response `error` and `code` is already agreed upon.
- 2026-09-25: The user decided that URL items may include both host and path (masking only query values). Output separated into components using `LogMasking.urlSummary`.

**Why:** Need field search in production CloudWatch / original URLs may contain tokens.
**How to apply:** When working on logging, keep the policy of assuming JSON and outputting values only via allowlist. For detailed configuration, see the "Logging" section in src/server/CLAUDE.md.
