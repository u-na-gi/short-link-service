# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

API server for the URL shortener. Scala 3.3 + Play Framework 3 (sbt), JDK 21. Twirl / static assets have been removed; JSON API only.
Requirements (`README.md` at the repository root): shorten and resolve URLs, the same URL gets the same short URL, no persistence needed (in memory is fine), the path is 8 random characters.

## Commands

Run in this directory (`src/server/`).

```sh
sbt run                  # Dev server (http://localhost:9000). Same as make dev
sbt test                 # All tests. Same as make test
sbt "testOnly domain.UrlSpec"                    # One class only
sbt "testOnly domain.UrlSpec -- -z \"substring\""  # Filter by test name (ScalaTest)
sbt scalafmtAll          # Format (make fmt). Config is .scalafmt.conf (dialect scala3, maxColumn 100)
```

The dev containers (server and front) and E2E run from the Makefile at the repository root (`/app`). Details in the root `README.md` and `/app/tests/README.md`.

```sh
make up    # Start server (sbt run) and front (Vite) with compose.yaml. Env vars come from the root .env
make e2e   # Start with the E2E-only compose, run the runn scenarios through front, and clean up
```

Domain rules and complex assertions go on the Scala side (`test/`), not in E2E.

## Architecture

Layered, clean-architecture-like structure. Dependencies go one way: outer → inner (`domain`).

- `domain/` — The core, with no dependency on Play.
  - `Url` is a value object for a validated URL. The constructor is private; create it only through `Url.from(raw): Either[Url.Error, Url]`. It parses and normalizes with okhttp's `HttpUrl` (punycode, lowercase host) and rejects schemes other than http/https, URLs with credentials, and URLs over 2048 characters.
  - `ShortLinkRepository` / `ShortLinkService` are traits. Implementations live outside. `ShortLinkService.issue(url)` assigns a unique code and saves it. `saveIfAbsent` makes the check and the save atomic, and returns duplicate code (`CodeTaken`), URL already registered (`UrlExists`), or count limit reached (`Full`). If the URL is already registered, it returns the existing link regardless of the limit.
  - `PublicBaseUrl` is the public URL of this service shown to users (scheme + host [+ port]). Short URLs are always built from it, never from the Host header (in production, requests come through the Cloudflare Worker and Tunnel so Host is `localhost:9000`, and Host can also be spoofed).
  - `ServiceHost` is this service's host name, derived from `PublicBaseUrl`. Used to reject self-referencing URLs (redirect loops).
  - `PublicBaseUrl.codeOf(raw)` extracts the code from a short URL of this service. It checks only the host name (same rule as `ServiceHost`), and the path must be a single segment of 8 alphanumeric characters. Query and fragment are ignored.
- `usecase/` — Business operations. `CreateShortLink.execute(rawUrl)` takes the raw string, does the conversion to a value object inside, and returns `Future[Either[CreateShortLinkError, ShortLink]]`. Errors are enums; no exceptions.
  - The same URL gets the same link. If `findByUrl` finds it registered, no code is assigned. Otherwise it leaves issuing to `ShortLinkService.issue` and maps its `CodeExhausted` / `StorageFull` to usecase errors.
  - `ResolveShortLink.execute(code)` looks up a link by code (for redirects). `fromShortUrl(raw)` interprets a pasted short URL with `codeOf`, looks it up, and returns `NotShortUrl` / `NotFound`.
- `service/DefaultShortLinkService` — Implementation of `ShortLinkService`. Generates an 8-character alphanumeric code with `SecureRandom`, calls `saveIfAbsent`, and on a code collision retries up to 10 times (`CodeExhausted` if exceeded). Retrying is a consequence of random codes, so it lives here, not in the usecase. Tests pin the codes by passing a code generator (`test/support/SequenceCodes`) to the primary constructor.
- `infra/inmemory/InMemoryShortLinkRepository` — Implementation with two `TrieMap`s, code → link and URL → link (lost on restart). Only writes are `synchronized`.
  - It has a count limit so the public write API cannot use up memory (`shortener.max-links`, env var `SHORTENER_MAX_LINKS`, default 100,000). `TrieMap.size` is O(n), so the count is kept in a counter inside the same lock as writes. `Module` validates the limit and passes it as `LinkCapacity` (startup stops if it is 0 or less). Tests use `maxLinks` on the primary constructor (default: no limit).
- `controllers/` — HTTP concerns only. The JSON shape is validated with `Reads` (`invalid_request`), and business errors map from usecase enums to `error` codes (400 `invalid_url` / `self_reference` / `not_short_url`, 404 `not_found`, 500 `code_generation_failed`, 503 `storage_full`). Create and resolve return the same shape (`code` / `shortUrl` / `originalUrl`).
  - The error body is only `{"error": code}`. Only `invalid_url` adds `reason` (`empty` / `malformed` / `unsupported_scheme` / `credentials` / `too_long`, same values as the front end's `UrlProblem`). Do not return input values, limits, validation details, or internal details. User-facing messages are built from the code by the front end (`src/front/src/api.ts`).
  - domain / usecase errors are returned as enums without messages. `PublicBaseUrl.from` errors are also `PublicBaseUrl.Error`; `Module` turns them into English messages and stops startup (a parse failure keeps the original exception as the cause).
- `app/Module.scala` — Collects the Guice bindings (trait → implementation). It validates the `shortener.base-url` setting (`conf/application.conf`, can be overridden with env var `SHORTENER_BASE_URL`; default is the front end's Vite `http://localhost:5173`) and `@Provides` it as `PublicBaseUrl` / `ServiceHost`, so usecases do not depend on Play's `Configuration`.

### Logging

`conf/logback.xml` writes one JSON line per event to stdout in both dev and production (logstash-logback-encoder). Locally, read it through jq with `make logs-server`.

- `logging/RequestIdFilter` — The outermost filter. Assigns a UUID to each request, stores it in an attribute (`RequestId.Key`), and returns it in the `X-Request-Id` response header. Play's `request.id` is a counter that restarts from 1 on restart, so it is not used. An incoming `X-Request-Id` can be spoofed, so it is not used either. Action exceptions are passed to ErrorHandler here with the request that has the ID (if left to Play, it is called with the original request without the attribute, and no ID is attached).
- `logging/AccessLogFilter` — Access log, one line per request (logger name `access`). Writes method, host (`X-Forwarded-Host` first), path, status, elapsed time, requestId, and the body and query. It sits outside Play's default filters so rejected requests are also logged. Health checks are DEBUG.
- `logging/LogMasking` keeps only the keys of the body and query and hides the values. The original URL's query may contain tokens, so it lists only the keys whose values may be shown (only `error` and `code` in responses). URL fields (`url` / `shortUrl` / `originalUrl`, `AccessLogFilter.UrlKeys`) are split by `LogMasking.urlSummary` into scheme, host, port, path (cut at 256 characters), and query keys, so we can trace what was sent (query values, fragment, and user info are not written; the name of a parameter without a value, `?token`, is hidden too). `MaskingJsonGeneratorDecorator` in `logback.xml` is a safety net that hides any string that looks like a URL with a scheme, whatever the field name.
- `controllers/ErrorHandler` — Returns Play's own errors as `{"error"}` JSON too (Play's messages may contain fragments of the body, so they go only to the DEBUG log). Unhandled exceptions are logged at ERROR with stack traces, and users get only `internal_error`. A 500 that is not an exception (`CodeExhausted`) is logged at ERROR by the controller.
- Logs other than the access log also get the requestId via `logging.RequestLog.marker(request)`. It is not attached automatically (Futures cross threads, so MDC is not used). If you forget it, you cannot tell which request a log belongs to.
- root is WARN, so your own loggers do not output INFO unless you add the logger name to `logback.xml` (currently `access` and `controllers`).

Routing is in `conf/routes`. `GET /` and `GET /api/v1/health` are health checks (through the front end, `/` is index.html, so E2E uses the latter), `POST /api/v1/links` creates a short link (`nocsrf` because there is no cookie session), `GET /api/v1/links/resolve?shortUrl=` resolves a short URL, and the last `GET /:code` redirects to the original URL with 302 (404 `not_found` if unknown). Play's `Redirect` defaults to 303, so `FOUND` is set explicitly.

Usecase tests do not use the DI container; they build with `new` and pass `SequenceCodes` to `DefaultShortLinkService` to pin codes (`test/usecase/CreateShortLinkSpec.scala`). Retry tests are in `test/service/DefaultShortLinkServiceSpec.scala`.

`play.filters.hosts.allowed` in `conf/application.conf` can take one extra host via env var `PLAY_EXTRA_ALLOWED_HOST`. In compose, the Vite proxy rewrites Host to the proxy target (`server:9000`), so `compose.yaml` passes `server`.

## Operational assumptions

Links are in memory, so it must always run as a single process. With multiple instances behind a load balancer, a created link returns 404 on another instance, and the same URL gets a different code. Production is one ECS on Fargate task (deploys do not run old and new side by side; see `docs/production-architecture.md` at the root). To scale, replace `ShortLinkRepository` with an implementation on a shared store (DynamoDB etc.).

## Conventions

- Comments and documents are in English. Comments explain "why".
- Server log messages are in English (include the stack trace for exceptions). User-facing messages are not returned by the API; the front end holds them.
- `.g8/` is Play's giter8 scaffold template, not app code.
