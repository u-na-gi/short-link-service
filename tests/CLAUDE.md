This directory is for E2E. Scenarios are written in runn (YAML).

- Place scenarios in `scenarios/*.yml`. Target is `${E2E_BASE_URL:-http://localhost:9000}`
- Run via `make e2e` at the repository root. Starts server / front using E2E-dedicated compose (`compose.e2e.yaml`), runs scenarios via front, and cleans up
- For syntax checks only: `runn list -l "tests/scenarios/*.yml" </dev/null` (hangs waiting for input if stdin is not closed)
- Write domain rules and complex assertions on the Scala side in `src/server/test/`
- For deployed environments (develop / staging): `make e2e-remote ENV=develop`. Reads Cloudflare Access service token from SSM and attaches it in headers
- Attach `*access` (vars anchor) to `headers` across all steps. If omitted, develop / staging returns Access login screen (302)
