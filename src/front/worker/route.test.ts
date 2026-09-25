import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  SERVER_ORIGIN,
  clientKey,
  limitTargetOf,
  rateLimited,
  serverUnavailable,
  toServerRequest,
} from "./route.ts";

describe("limitTargetOf", () => {
  test("passes the API to Play as api", () => {
    assert.equal(limitTargetOf("/api/v1/links"), "api");
    assert.equal(limitTargetOf("/api/v1/links/resolve"), "api");
  });

  test("passes a single segment of 8 alphanumeric chars to Play as a short URL (redirect)", () => {
    assert.equal(limitTargetOf("/Xk3pR8vN"), "redirect");
    assert.equal(limitTargetOf("/abcd1234"), "redirect");
  });

  test("treats everything else as static assets", () => {
    assert.equal(limitTargetOf("/"), null);
    assert.equal(limitTargetOf("/index.html"), null); // contains a symbol
    assert.equal(limitTargetOf("/assets/index-abc.js"), null);
    assert.equal(limitTargetOf("/abcd123"), null); // 7 chars
    assert.equal(limitTargetOf("/abcd12345"), null); // 9 chars
    assert.equal(limitTargetOf("/abcd1234/x"), null); // 2 segments
    assert.equal(limitTargetOf("/api"), null); // does not start with /api/
  });
});

describe("clientKey", () => {
  test("uses the client IP added by Cloudflare and ignores X-Forwarded-For", () => {
    const req = new Request("https://example.com/", {
      headers: { "cf-connecting-ip": "198.51.100.7", "x-forwarded-for": "203.0.113.1" },
    });
    assert.equal(clientKey(req), "198.51.100.7");
  });
});

describe("rateLimited", () => {
  test("returns a 429 in the same shape as API errors", async () => {
    const res = rateLimited();
    assert.equal(res.status, 429);
    assert.equal(res.headers.get("retry-after"), "60");
    assert.deepEqual(await res.json(), { error: "rate_limited" });
  });
});

describe("toServerRequest", () => {
  // The body is a stream that can be read only once, so build it per test
  const original = () =>
    new Request("https://short-link-develop.example.workers.dev/api/v1/links?x=1", {
      method: "POST",
      headers: {
        cookie: "CF_Authorization=secret",
        "cf-access-jwt-assertion": "jwt",
        "x-forwarded-for": "203.0.113.1",
        "x-turnstile-token": "token",
        "content-type": "application/json",
      },
      body: JSON.stringify({ url: "https://example.com/" }),
    });

  test("points to the Play address, keeping the path and query", () => {
    assert.equal(toServerRequest(original()).url, `${SERVER_ORIGIN}/api/v1/links?x=1`);
  });

  test("passes the method and body as is", async () => {
    const req = toServerRequest(original());
    assert.equal(req.method, "POST");
    assert.equal(await req.text(), JSON.stringify({ url: "https://example.com/" }));
  });

  test("drops credentials and the user's X-Forwarded-For, and passes the original host and scheme in X-Forwarded-*", () => {
    const req = toServerRequest(original());
    assert.equal(req.headers.get("cookie"), null);
    assert.equal(req.headers.get("cf-access-jwt-assertion"), null);
    assert.equal(req.headers.get("x-forwarded-for"), null);
    assert.equal(req.headers.get("x-turnstile-token"), null);
    assert.equal(req.headers.get("content-type"), "application/json");
    assert.equal(req.headers.get("x-forwarded-host"), "short-link-develop.example.workers.dev");
    assert.equal(req.headers.get("x-forwarded-proto"), "https");
  });

  test("does not follow redirects (returns the short URL's 302 as is)", () => {
    assert.equal(toServerRequest(original()).redirect, "manual");
  });
});

describe("serverUnavailable", () => {
  test("returns a 502 in the same shape as API errors", async () => {
    const res = serverUnavailable();
    assert.equal(res.status, 502);
    assert.deepEqual(await res.json(), { error: "server_unavailable" });
  });
});
