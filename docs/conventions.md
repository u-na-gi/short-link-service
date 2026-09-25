# Development Conventions and Design Policies (`docs/conventions.md`)

This document describes the policies, conventions, and design decisions for writing code and documentation in this repository.

## Table of Contents

- [Language and Comment Conventions](#language-and-comment-conventions)
- [Error Handling Policy (Eliminating Exceptions)](#error-handling-policy-eliminating-exceptions)
- [Error Information Protection and Message Design](#error-information-protection-and-message-design)
- [Logging Conventions](#logging-conventions)

---

## Language and Comment Conventions

- **Documents, comments, test names, UI text, and CI names are written in English**:
  - Documentation (Markdown), source code comments, test names, UI text, and CI names in this repository are standardized in English.
- **Describe "why it is done"**:
  - Avoid comments that merely trace code operations; describe the **design intent and background rationale**, such as "why that design/algorithm was chosen" or "why that constraint/workaround is necessary".
- **Log messages stay English**:
  - Log messages output inside the server (Logback) are unified in English.

---

## Error Handling Policy (Eliminating Exceptions)

- **No exceptions in business logic**:
  - In the domain layer (`domain`) and usecase layer (`usecase`), runtime exceptions are not thrown (`throw`) to represent business errors.
  - All failures are represented and returned type-safely using the `Either[ErrorEnum, Result]` type signature.
- **Unified handling at the controller layer**:
  - The controller layer maps `Either` error enums returned by use cases to HTTP status codes and JSON error codes.
  - Unexpected unhandled exceptions inside the server are caught by `ErrorHandler` and uniformly handled as HTTP 500 (`internal_error`).

---

## Error Information Protection and Message Design

- **Non-disclosure of internal information and input values**:
  - As a security measure (against information leakage and phishing), API error responses do not include exception messages, stack traces, internal parameter maximum limits, or submitted input values.
  - As a rule, only the `{"error": "<error_code>"}` format is returned (only `invalid_url` includes `reason` so that the frontend can branch guidance messages).
- **User-facing messages generated on the frontend**:
  - The API does not return human-readable messages; instead, the frontend (`src/front/src/api.ts`) receives error codes and constructs guidance messages that let users intuitively understand what to do next.

---

## Logging Conventions

- **Always output in JSON format**:
  - Regardless of dev or production environments, structured logs are output to stdout at one JSON line per request (`logstash-logback-encoder`).
- **Value masking protection (`LogMasking`)**:
  - Because query parameters of original URLs may contain tokens or personal information, request and response bodies and queries retain only key names as a rule, masking values with `***`.
  - Allowed fields for value output are strictly managed via an allowlist (only `error` and `code` in responses).
