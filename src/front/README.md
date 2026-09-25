# Front end (`src/front`)

A single-page web application that shortens URLs and resolves short URLs. No login required; it can be used right away.

## Contents

- [Tech stack](#tech-stack)
- [Files and roles](#files-and-roles)
- [Development commands](#development-commands)
- [Dev proxy and server connection](#dev-proxy-and-server-connection)
- [Spec and implementation policy](#spec-and-implementation-policy)
- [Production build](#production-build)

---

## Tech stack

| Category                  | Technology             | Version / notes   |
| ------------------------- | ---------------------- | ----------------- |
| Runtime / package manager | [Bun](https://bun.sh/) | 1.4+              |
| UI library                | React                  | 19.3              |
| Build tool / dev server   | Vite                   | 8.3               |
| Language                  | TypeScript             | 7.0               |
| Code formatter            | Prettier               | 3.9               |
| Test runner               | Bun Test               | `src/url.test.ts` |

---

## Files and roles

```
src/front/
├── Dockerfile          # Dev container definition (based on oven/bun)
├── package.json        # Dependencies and scripts
├── vite.config.ts      # Vite config (proxy and listen host)
├── tsconfig.json       # TypeScript config
├── index.html          # Entry HTML
└── src/
    ├── main.tsx        # React application entry point
    ├── App.tsx         # Main component (shorten form, result display, copy)
    ├── ResolveForm.tsx # Resolve form component (original URL from a short URL)
    ├── api.ts          # API client, response types, error message mapping
    ├── url.ts          # Simple URL validation before sending, error text
    ├── url.test.ts     # Unit tests for url.ts
    └── styles.css      # Stylesheet
```

### Responsibilities of the main files

- **`src/App.tsx`**:
  - Handles the "shorten form" at the top of the page and shows the result.
  - Shows the created short URL as a link, copies it to the clipboard, and shows the original URL.
  - Places `ResolveForm` at the bottom of the page.
- **`src/ResolveForm.tsx`**:
  - Handles the "resolve form" at the bottom of the page and shows the result.
  - Takes a whole short URL and shows the original URL.
- **`src/url.ts`**:
  - Validation logic (`findUrlProblem`) that detects "clearly invalid input" before sending.
  - Defines error kinds (`UrlProblem`) that mirror the server's `domain.Url`.
- **`src/api.ts`**:
  - Talks to the backend API (`/api/v1/links`, `/api/v1/links/resolve`).
  - Turns machine-readable error codes from the server (`invalid_url`, `self_reference`, `not_short_url`, `not_found`, etc.) into English messages that make it clear to the user what to do next.

---

## Development commands

Run these commands in this directory (`src/front/`).

```sh
# Install dependencies
bun install

# Start the dev server (http://localhost:5173)
bun run dev

# Run TypeScript type checking only
bun run typecheck

# Type check and production build (output goes to dist/)
bun run build

# Run unit tests (url.test.ts)
bun run test

# Format code with Prettier
bun run format

# Check formatting with Prettier
bun run format:check
```

---

## Dev proxy and server connection

### Reverse proxy with Vite (`vite.config.ts`)

In local development, Vite's reverse proxy is set up so the browser sees everything as a single origin, `http://localhost:5173`.

- **`/api/*`**: Forwarded to the backend JSON API (`API_ORIGIN`, default `http://localhost:9000`).
- **`^/[A-Za-z0-9]{8}(\\?.*)?$`**: Requests to short URLs are forwarded to the backend's 302 redirect handling.
- **Same origin**: Thanks to the proxy, the browser can call the API without dealing with CORS.
- **`allowedHosts: ["front"]`**: In the E2E test environment the runn container connects with `Host: front:5173`, so it is added as an allowed host in Vite.

### Connection by environment

1. **Docker Compose (`make up`)**:
   - `compose.yaml` starts the front end and backend together.
   - `API_ORIGIN=http://server:9000` is passed to the container.
   - Source changes on the host show up in the browser right away through HMR.
2. **Running directly on the host machine**:
   - If you run `bun run dev` without containers, **start `sbt run` (port 9000) in the `src/server` directory first**.
   - To change the target, set the environment variable: `API_ORIGIN=http://host:port bun run dev`.

---

## Spec and implementation policy

### 1. Page behavior and UX

- **Send on paste**: Both the shorten form and the resolve form send a request as soon as a URL is pasted (`onPaste` event). Pressing Enter or clicking the button also sends.
- **One-click copy**: A copy button next to the created short URL uses the Clipboard API (`navigator.clipboard.writeText`) for easy sharing.
- **Guiding between forms**: If a short URL that was already created is entered in the shorten form at the top, the server returns a `self_reference` error, and the user is guided to the right form with "This URL is already shortened. To resolve it, paste it in the field below."

### 2. Validation before sending (`src/url.ts`)

Checks run before sending to avoid wasted requests and give fast feedback.

- **Checks**:
  - Empty string (`empty`)
  - Over 2,048 characters (`too_long`)
  - Missing scheme / scheme other than `http`, `https` (`unsupported_scheme` / `malformed`)
  - Not parseable as a URL (`malformed`)
  - URL containing a user name or password (`credentials`)
- **"Never stricter than the server" policy**:
  Checks that are too strict on the client risk rejecting valid URLs that the server accepts. So the front end rejects only "clearly invalid formats" and leaves the final decision, such as Punycode conversion and domain validity, to the backend.

### 3. Error handling and message design (`src/api.ts`)

- For security reasons, the backend API does not include exception details or input values in responses; it returns only machine-readable error codes.
- `api.ts` is responsible for turning them into English messages that make it clear to the user what to do next, and shows them on the page.
  - `invalid_url` (with `reason`): guidance based on what is wrong with the URL format
  - `self_reference`: guidance to the resolve form
  - `not_short_url`: says it is not a short URL created by this service
  - `not_found`: says no matching short URL was found
  - `code_generation_failed`: asks the user to try again later

---

## Production build

```sh
bun run build
```

This runs TypeScript type checking (`tsc --noEmit`), then Vite writes optimized static files to the `dist/` directory.
In production, the `dist/` output is served as static assets of a Cloudflare Worker (`make deploy-front ENV=...`). The Worker code is in `worker/` and its config is `wrangler.jsonc`. See [docs/production-architecture.md](../../docs/production-architecture.md) for the architecture.
