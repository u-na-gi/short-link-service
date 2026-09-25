# Work Plan: Development Compose / E2E Compose / Short URL Resolve Feature

Agreed plan on 2026-09-23. Recorded here because rebuilding the devcontainer resets Claude Code sessions.
Update checks under "Progress" as work proceeds.

## Background

Among the requirements ("Service Requirements" in README), the following two were unachieved:

- Short URL domain shall be `https://example.com/`
  - The server can accept this via `shortener.base-url` (overridden by `SHORTENER_BASE_URL`), but nowhere was passing the value.
- Implement feature to resolve short URLs back to original URLs
  - The server only has `GET /:code` 302 redirection. There is no API or UI to look up the original URL from a short URL.

## Decisions

### Development Environment

- Manage environment variables via `.env` at root. Commit `.env.example` and add `.env` to `.gitignore`.
  - Content is `SHORTENER_BASE_URL=https://example.com/`. Pass to compose via `env_file: .env`.
- Place `compose.yaml` for development at root and start with `make up`.
  - Place Dockerfiles at `src/server/Dockerfile` and `src/front/Dockerfile`.
- Do not assume entering the container to work. However, hot reload is required.
  - Run server with `sbt run`, front with `bun run dev`.
  - If E2E passes against front, consider hot reload verified as well. Do not verify by modifying files and watching.
- Short URLs return `https://example.com/xxxxxxxx`. Per specification, they do not need to open locally.
- Devcontainer uses host Docker (docker-outside-of-docker), so bind mount paths are interpreted on the host.
  - Add `"remoteEnv": {"LOCAL_WORKSPACE_FOLDER": "${localWorkspaceFolder}"}` to `.devcontainer/devcontainer.json`.
  - Set compose mount source to `${LOCAL_WORKSPACE_FOLDER:-.}/src/server`. When run from the host, it evaluates to `.`.
  - `env_file` and build context are read by whichever side runs compose, so host path is unnecessary.
  - Do not add a "stop if variable is empty" check to Makefile (user decision).
- Remove `forwardPorts: [9000, 5173]` from `.devcontainer/devcontainer.json` to avoid collision with ports exposed by compose.

### E2E

- E2E uses a dedicated compose, started separately from development.
  - Layer onto development definition like `docker compose -p short-link-e2e -f compose.yaml -f compose.e2e.yaml`, separating project names.
  - Tasks for `compose.e2e.yaml`:
    - Add runn service, run scenarios against `http://front:5173`
    - Remove port publishing
    - Pass fixed `SHORTENER_BASE_URL=https://example.com` and `E2E_SELF_URL=https://example.com/abcd1234`
  - `make e2e` performs startup -> wait for runn completion -> `down`. Share volume for sbt cache to avoid re-downloading dependencies each time.
- Add `front` to Vite's `allowedHosts`, because runn arrives with `Host: front:5173`.
  - Keep Vite proxy without `changeOrigin`. Confirm during implementation that Play's AllowedHostsFilter receives `Host: localhost:5173` / `front:5173`.
- `tests/scenarios/health.yml` fails via front because `GET /` returns `index.html`.
  - Add `GET /api/v1/health` to server, point `health.yml` there.
- `tests/e2e.ts` no longer needs to start `sbt run` locally. Choose whichever is simpler during implementation: replace with compose and delete, or rewrite as a script controlling compose.

### Resolving Short URLs

- Frontend: Place a short URL input form below the existing shorten form. Display original link below it.
- API: `GET /api/v1/links/resolve?shortUrl=https://example.com/Xk3pR8vN`

  | Status | Content |
  | --- | --- |
  | 200 | `{code, shortUrl, originalUrl}` (same shape as create API) |
  | 400 | `invalid_request` (`shortUrl` missing) |
  | 400 | `not_short_url` (not the shape of this service's short URL) |
  | 404 | `not_found` (correct shape but unregistered) |

- Place short URL validation logic on the server, since only the server knows the public URL.
  - Add `PublicBaseUrl.codeOf(raw): Option[String]`. Returns code only when host matches public URL and path is `/[A-Za-z0-9]{8}`.
- usecase: Add operation to `ResolveShortLink` to look up by short URL string.
  - Return type is `Either[ResolveShortLinkError, ShortLink]`. Errors are `NotShortUrl` / `NotFound`.
  - Keep existing `GET /:code` redirect.
- Tests:
  - Scala: `PublicBaseUrlSpec` / `ResolveShortLinkSpec` / `LinkControllerSpec`
  - E2E: Add happy path, 404, `not_short_url` to `tests/scenarios/resolve_link.yml`

## Progress

1. Development environment
   - [x] `.env.example` / `.env` / `.gitignore`
   - [x] `src/server/Dockerfile` (JDK 21 + sbt 1.13.0, uid 1000, `CMD sbt run`)
   - [x] `src/front/Dockerfile` (bun, `bun install`, `CMD bun run dev`)
   - [x] `compose.yaml`
     - server: Separate `target/` and `project/target/` with named volumes (so they don't conflict with devcontainer sbt / Metals)
     - server: Volume-mount sbt / coursier caches as well
     - server: Add `stdin_open: true` and `tty: true` (since `sbt run` stops when stdin is closed)
     - front: Separate `node_modules` with volume. Pass `API_ORIGIN=http://server:9000`
   - [x] `.devcontainer/devcontainer.json` (add `remoteEnv`, remove `forwardPorts`)
   - [x] Root `Makefile` (`up` / `down` / `logs`)
   - [x] Verified `docker compose config` and `docker compose build` pass (startup not yet)
     - uid in image is 1000, java path is `/usr/lib/jvm/java-21-openjdk-amd64` matching devcontainer
     - Placed `.dockerignore` in server (exclude all) and front (only package.json and bun.lock)
   - [x] Rebuild / Reopen devcontainer (done by user)
     - Confirmed `LOCAL_WORKSPACE_FOLDER` contains host path
     - Session restored via `claude -r`
2. E2E environment
   - [x] `compose.e2e.yaml` / `make e2e`
     - Run via `docker compose -p short-link-e2e -f compose.yaml -f compose.e2e.yaml run --rm runn`, clean up with `rm -fsv` and `down`
     - front healthcheck calls `/api/v1/health` via Vite. When this passes, server is also started
     - sbt / coursier caches share development volumes by `name:`. `target` is separate
   - [x] Add `GET /api/v1/health`, repoint `health.yml`
   - [x] Align `E2E_SELF_URL` (`https://example.com/abcd1234` in `compose.e2e.yaml`)
   - [x] Add `front` to Vite's `allowedHosts`
   - [x] Deleted `tests/e2e.ts`, removed `e2e` script from `tests/package.json`
   - [x] Confirmed current scenarios pass with `make up` -> `make e2e` (4 scenarios, 0 failures)
   - Findings during implementation
     - Vite proxy rewrites Host to proxy destination (`server:9000`).
       Rejected by Play's AllowedHostsFilter, so added
       `play.filters.hosts.allowed += ${?PLAY_EXTRA_ALLOWED_HOST}` to `application.conf`
       and passed `server` in `compose.yaml`
     - In `make e2e`, compose outputs a warning when using dev cache volumes from another project. Does not affect operation
3. Resolving short URLs
   - [x] Server (domain / usecase / controller / routes / Scala tests)
     - Added `Url.path`, `PublicBaseUrl.codeOf`, `ResolveShortLink.fromShortUrl`, `LinkController.resolve` (79 tests passed)
     - Verified 200 / 404 / 400 `not_short_url` / 400 `invalid_request` in running dev compose. Also verified Play hot reload (RELOAD)
   - [x] E2E scenarios (Added 4 steps to `resolve_link.yml`. Passed `runn list` syntax check)
   - [x] Confirmed passing with `make e2e` (4 scenarios, 16 steps, 0 failures)
   - [x] Frontend (`resolveShortUrl` in `api.ts`, `ResolveForm.tsx`, error messages)
     - Passed `bun run typecheck` and prettier. Visual appearance in browser not yet verified
4. Documentation
   - [x] Wrote root `README.md` (architecture, `make up` / `make e2e`, `.env`)
   - [x] `src/server/CLAUDE.md` (make description, resolve API, `PLAY_EXTRA_ALLOWED_HOST`)
   - [x] `src/front/README.md` (startup via compose, 2 forms on screen)
   - [x] `tests/README.md` / `tests/CLAUDE.md` (removal of `e2e.ts`, E2E via compose)
   - Also updated `make e2e` on failure to output tail of server / front logs before cleanup

## Rules to Follow

- `/app/CLAUDE.md`: Do not implement or edit without user approval. Do not start servers without permission.
  - This plan itself is approved. However, running `make up` or `make e2e` starts servers, so ask first before executing.
- Comments and documentation are in English. In comments, state "why" it is done.
