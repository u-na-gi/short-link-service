# Backend API server (`src/server`)

A JSON API server that shortens and resolves URLs. It is built with Scala 3 and Play Framework 3, and runs as a pure API server without Twirl or static asset handling.

## Contents

- [Tech stack](#tech-stack)
- [Development commands](#development-commands)
- [HTTP API spec](#http-api-spec)
  - [Endpoints](#endpoints)
  - [Error response rules](#error-response-rules)
  - [Error codes](#error-codes)
  - [Request tracing (`X-Request-Id`)](#request-tracing-x-request-id)
- [Architecture and layers](#architecture-and-layers)
- [Core domain and business logic](#core-domain-and-business-logic)
- [Logging design](#logging-design)
- [Testing approach](#testing-approach)

---

## Tech stack

| Category                        | Technology                         | Version / notes                      |
| ------------------------------- | ---------------------------------- | ------------------------------------ |
| Language                        | Scala                              | 3.3.6                                |
| Web framework                   | Play Framework                     | 3.0.11 (JSON API only)               |
| Build tool                      | sbt                                | 1.13.0                               |
| Runtime                         | JDK                                | 21 (Eclipse Temurin)                 |
| DI container                    | Guice                              | Built into Play                      |
| JSON library                    | Play JSON                          | `Reads` / `Writes`                   |
| HTTP client (for URL checks)    | okhttp                             | 4.12.0 (strict parsing via `HttpUrl`) |
| Logging                         | Logback + logstash-logback-encoder | 9.0 (structured JSON logs)           |
| Test framework                  | ScalaTest + scalatestplus-play     | 7.0.2                                |

---

## Development commands

Run in this directory (`src/server/`).

```sh
# Start the dev server (listens on http://localhost:9000)
sbt run

# Run all tests (currently 79)
sbt test

# Run only one test class
sbt "testOnly domain.UrlSpec"

# Run tests filtered by name (ScalaTest)
sbt "testOnly domain.UrlSpec -- -z \"normalize\""

# Format code (config: .scalafmt.conf)
sbt scalafmtAll
```

> [!NOTE]
> To start everything together with the front end and E2E, use `make up` or `make e2e` at the repository root. See [docs/development.md](../../docs/development.md) for details.

---

## HTTP API spec

Play Framework listens on port `9000`.
In local development, the Vite dev server (`localhost:5173`) proxies `/api/*` and `/{8 alphanumeric characters}` to Play, so the browser sees a single origin at `localhost:5173`.

### Endpoints

| Method and path                          | Purpose                                                                                                                                                         | Success response                                                                  | Main errors                                                                                        |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `GET /`                                  | Health check (direct to the API server)                                                                                                                         | 200 `{"status":"ok"}`                                                             | -                                                                                                  |
| `GET /api/v1/health`                     | Health check (through the front end / proxy)                                                                                                                    | 200 `{"status":"ok"}`                                                             | -                                                                                                  |
| `POST /api/v1/links`                     | Create a short link. Body is `{"url": "..."}`. Excluded from CSRF checks (`+ nocsrf`) because there is no cookie session.                                        | 201 `{ "code": "...", "shortUrl": "...", "originalUrl": "..." }`                  | 400 `invalid_request`<br>400 `invalid_url`<br>400 `self_reference`<br>500 `code_generation_failed` |
| `GET /api/v1/links/resolve?shortUrl=...` | Resolve a short URL. Takes the whole short URL as a query parameter and returns the original URL. The server decides whether it is this service's URL and which part is the code. | 200 `{ "code": "...", "shortUrl": "...", "originalUrl": "..." }` (same shape as create) | 400 `invalid_request`<br>400 `not_short_url`<br>404 `not_found`                                    |
| `GET /:code`                             | Redirect for a short URL. Placed last in the routes.                                                                                                            | 302 `Location: <original URL>` (302 set explicitly instead of Play's default 303) | 404 `{"error":"not_found"}`                                                                        |

### Error response rules

For security (no information leaks) and simplicity, the error response body is, as a rule, only `{"error": "<error code>"}`.

- Input values, internal limits, exception messages, and stack traces are not included in the response.
- Only `invalid_url` adds `reason`, so the front end can give the user proper guidance.
- User-facing messages are built from the error code on the front end (`src/front/src/api.ts`).

### Error codes

| Status     | `error`                                                                          | `reason` (only when present)                                                  | When                                                                                                    |
| ---------- | -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| 400        | `invalid_request`                                                                | -                                                                             | JSON parse failure, required field `url` missing or not a string, `shortUrl` query missing, Play's own 400 errors |
| 400        | `invalid_url`                                                                    | `empty`<br>`too_long`<br>`unsupported_scheme`<br>`malformed`<br>`credentials` | The URL format is invalid. See [Core domain and business logic](#core-domain-and-business-logic) below  |
| 400        | `self_reference`                                                                 | -                                                                             | Tried to shorten a short URL of this service itself (prevents redirect loops)                           |
| 400        | `not_short_url`                                                                  | -                                                                             | The URL passed to `resolve` does not match this service's short URL format (domain, path)               |
| 404        | `not_found`                                                                      | -                                                                             | The given code or short URL was never issued (or was lost on restart)                                   |
| 500        | `code_generation_failed`                                                         | -                                                                             | Retries on random code collisions hit the limit (10)                                                    |
| 500        | `internal_error`                                                                 | -                                                                             | Unhandled exception in the server (caught by the error handler)                                         |
| Other      | `forbidden`<br>`payload_too_large`<br>`unsupported_media_type`<br>`client_error` | -                                                                             | Play Framework's built-in errors converted to JSON by `ErrorHandler`                                    |

### Request tracing (`X-Request-Id`)

- Every HTTP response has an `X-Request-Id` header (a UUID issued by the server).
- An `X-Request-Id` sent by the client is not used, to prevent spoofing; the server always issues a new one.
- Play's built-in `request.id` (a counter that goes back to 1 on restart) is not used either.

---

## Architecture and layers

The layers follow a clean-architecture-like structure where dependencies go one way (outer → inner `domain`).

```
src/server/app/
├── domain/                  # Core domain with no dependency on Play
│   ├── Url.scala            # Value object for a validated, normalized URL
│   ├── ShortLink.scala      # Short link model (code, url)
│   ├── ShortLinkRepository.scala # Repository interface (trait)
│   ├── ShortLinkService.scala    # Code assignment / issuing service interface (trait)
│   ├── PublicBaseUrl.scala  # Value object for this service's public base URL
│   └── ServiceHost.scala    # Value object for this service's host name (for self-reference checks)
├── usecase/                 # Application business operations (return Either, no exceptions)
│   ├── CreateShortLink.scala  # Use case: create a short link
│   └── ResolveShortLink.scala # Use case: resolve / look up a short link
├── service/                 # Concrete domain service implementations
│   └── DefaultShortLinkService.scala # 8-character codes with SecureRandom and retry control
├── infra/inmemory/          # Infrastructure layer (in-memory implementation)
│   └── InMemoryShortLinkRepository.scala # Thread-safe in-memory storage with TrieMap
├── controllers/             # HTTP adapter layer
│   ├── LinkController.scala # Actions for shorten, resolve, and redirect
│   ├── HomeController.scala # Health check
│   └── ErrorHandler.scala   # Handler that also turns Play's own errors into JSON
├── logging/                 # Structured logging and masking
│   ├── RequestIdFilter.scala # Assigns a UUID to each request
│   ├── AccessLogFilter.scala # JSON access log, one line per request
│   └── LogMasking.scala     # Masks secret values in query and body
└── Module.scala             # Guice DI setup and config validation at startup
```

### Flow control without exceptions

- The domain and usecase layers do not `throw` business exceptions.
- Every result is returned as `Future[Either[ErrorEnum, Result]]`, and the controller layer maps it to an HTTP status code and JSON.

---

## Core domain and business logic

### 1. URL validation and normalization (`domain.Url`)

The `Url` constructor is `private`; instances can only be created through the factory method `Url.from(raw): Either[Url.Error, Url]`.

1. **Trim whitespace**: Trim leading and trailing whitespace. `Url.Error.Empty` if empty.
2. **Length limit**: `Url.Error.TooLong` if longer than 2,048 characters after trimming.
3. **Scheme check**: Extract the scheme with a regex and allow only `http` or `https`. Otherwise `Url.Error.UnsupportedScheme` (rejects bad schemes such as `javascript:` and `data:`).
4. **Parsing**: Parse strictly with okhttp's `HttpUrl.parse`. `Url.Error.Malformed` if it cannot be parsed or has no host.
5. **No credentials**: If it contains user info in the form `user:pass@host`, `Url.Error.ContainsCredentials`, to prevent phishing.
6. **Normalization**: Lowercase the host name and convert internationalized domain names (IDN) to Punycode. The normalized string is kept, and its length is checked again.
   - The same URL written in different ways (mixed case, not yet Punycode) all converge to the same normalized URL.

### 2. Code assignment and collision handling (`DefaultShortLinkService`)

- **Code format**: 8 alphanumeric characters (`[a-zA-Z0-9]`), generated randomly with `SecureRandom`.
- **Reuse existing URLs**: Before assigning a code, check `ShortLinkRepository.findByUrl`; if the URL is already registered, return that link.
- **Collision retry**: If the generated code is already used by another URL, try a new code up to 10 times. If it collides 10 times in a row, return `CodeExhausted` (500 `code_generation_failed`).
- **Concurrency safety**: `InMemoryShortLinkRepository.saveIfAbsent` is mutually exclusive (`synchronized`), so concurrent requests for the same URL do not cause duplicate entries or races.

### 3. Public URL and self-reference prevention (`PublicBaseUrl`)

- **Independent of the Host header**: Short URLs are built from the setting `shortener.base-url`, not from the request's `Host` header (prevents host leaks and spoofing behind a reverse proxy).
- **Reject self-references (`self_reference`)**: Shortening a URL that points at this service's host name (`ServiceHost`) is a 400 error, to prevent infinite redirect loops.
- **Resolve check (`codeOf`)**: The `resolve` API extracts a code only when the URL's host matches this service and the path is a single segment `/[A-Za-z0-9]{8}`.

---

## Logging design

In both development and production, logs go to stdout **as JSON, one line per request** (`logstash-logback-encoder`). There is no text log format.

### Access log (`access` logger)

`AccessLogFilter` writes the following JSON as one line when each request finishes.

- `requestId`: UUID issued by the server (`X-Request-Id`)
- `method`, `path`, `status`, `elapsedMs`
- `host`: `X-Forwarded-Host` takes priority
- `requestBody`, `responseBody`: masked data

### Masking secrets (`LogMasking`)

- The original URL's query parameters etc. may contain secrets or tokens, so request / response bodies and queries **keep only the key names, and values are masked as `***`, as a rule**.
- Only the response's `error` code and the short `code` are allowed to show their values.
- On top of that, the decorator in `conf/logback.xml` masks `url`, `shortUrl`, and `originalUrl` automatically even if they end up in a log message, as defense in depth.

---

## Testing approach

The tests in this directory (`src/server/test/`) cover the domain logic thoroughly and check controller input and output.

- **Minimal mocks / stubs**: Use case tests (`CreateShortLinkSpec`) build objects directly with `new`, without the DI container.
- **Fixed random codes**: `test/support/SequenceCodes` fixes the order of codes generated during tests, so collision retry and deduplication behavior are tested deterministically.
