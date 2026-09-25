# short-link-service

A web service that shortens URLs and handles redirection and resolution from short URLs back to original URLs.

## Table of Contents

- [Service Requirements](#service-requirements)
- [System Architecture and Documentation](#system-architecture-and-documentation)
- [Deployed Environments](#deployed-environments)
- [Quickstart](#quickstart)

---

## Service Requirements

This service is implemented based on the following requirements:

- **URL shortening**: Convert original URLs to short URLs.
- **URL resolution**: Input a short URL to retrieve the original URL.
- **Redirection**: 302 redirect visits on short URLs to original URLs.
- **Same code returned for same URL**: If the same original URL is specified, always return the same short URL (no duplicate registration).
- **No data persistence required**: Link data is managed in-memory (may disappear on service stop or restart).
- **Short URL domain**: `https://example.com/`.
- **Short code format**: 8 random alphanumeric characters (e.g., `https://www.example.org/` → `https://example.com/Xk3pR8vN`).

---

## System Architecture and Documentation

This repository consists of a frontend, a backend API server, and E2E test scenarios. Refer to each document for detailed specifications, development steps, and design policies.

```mermaid
flowchart LR
    subgraph Client ["Client"]
        Browser["Browser"]
        Runn["E2E Tests (runn)"]
    end

    subgraph Front ["Frontend (Vite :5173)"]
        ReactApp["React SPA"]
        Proxy["Vite Proxy (/api/*, /{code})"]
    end

    subgraph Server ["Backend (Play :9000)"]
        LinkAPI["LinkController (JSON API / Redirect)"]
        MemoryStore[("InMemory Repository")]
    end

    Browser -->|HTTP| ReactApp
    ReactApp -->|fetch| Proxy
    Runn -->|HTTP| Proxy
    Proxy -->|Proxy| LinkAPI
    LinkAPI --> MemoryStore
```

| Target | Documentation | Role and Tech Stack |
| --- | --- | --- |
| **API Server** | [src/server/README.md](src/server/README.md) | Scala 3.3.6 + Play Framework 3.0.11 (sbt 1.13.0), JDK 21. JSON API only. HTTP API specifications, core domain logic, logging design, ScalaTest. |
| **Frontend** | [src/front/README.md](src/front/README.md) | React 19 + TypeScript 7 + Vite 8 (Bun). Single-page SPA without login. Vite proxy configuration, UI specifications, client validation. |
| **E2E Tests** | [tests/README.md](tests/README.md) | [runn](https://github.com/k1LoW/runn) (YAML runbook). External integration and connectivity test scenarios against running services. |
| **Development Guide** | [docs/development.md](docs/development.md) | Procedures for Docker Compose and direct local startup, hot reload, environment variables, detailed Devcontainer setup. |
| **Production Architecture** | [docs/production-architecture.md](docs/production-architecture.md) | Single-process constraint (in-memory retention), production setup (Cloudflare Worker + Tunnel + ECS on Fargate), CI/CD, procedures for release, E2E, and destroy. |
| **Conventions and Design Policy** | [docs/conventions.md](docs/conventions.md) | Language policy for comments and documentation, error handling without exceptions, information protection, logging conventions. |

---

## Deployed Environments

> **This service is suspended (2026-09-25).** AWS ECS and Cloudflare Worker / Tunnel / Access have been removed across all 3 environments, so the URLs below cannot be reached. Automatic deployment (the `deploy` workflow) is disabled. The table below is a record of when it was running. See [docs/production-architecture.md](docs/production-architecture.md) for recreate procedures.

| Environment | URL | Trigger | Access Scope | Short URL Domain |
| --- | --- | --- | --- | --- |
| develop | `https://s-dev.u-na-gi.com` | push to `develop` branch | Login required via Cloudflare Access | `https://example.com` (Requirement) |
| staging | `https://s-stg.u-na-gi.com` | push to `main` branch | Login required via Cloudflare Access | Same as site |
| prod | `https://s.u-na-gi.com` | `v*` tag (Requires owner approval) | Public | Same as site |

- Short URLs issued by develop (`https://example.com/xxxxxxxx`) cannot be opened directly. Restore them to the original URL using the resolve form on the site.
- The hostname appears in two places: `hostname` in `infra/terraform/envs/<env>/main.tf` and `routes` in `src/front/wrangler.jsonc`, and must match (checked on deploy). The short URL domain is the `shortener_base_url` output of `infra/terraform/envs/<env>`.

---

## Quickstart

Start the development environment using Docker Compose.

```sh
# 1. Configure environment variables (first time only)
cp .env.example .env

# 2. Start the development environment
make up

# 3. View logs
make logs-server   # Format and display server JSON logs with jq

# 4. Stop
make down
```

- **Frontend UI**: [http://localhost:5173](http://localhost:5173)
- **API Server (Play)**: [http://localhost:9000](http://localhost:9000)

> [!NOTE]
> On initial startup, Play Framework compilation may take a few minutes before responding to the first request.
> For detailed development environment setup and instructions on running directly on the host, see [docs/development.md](docs/development.md).
