import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { turnstileActionOf, turnstileFailed, verifyTurnstile } from "./turnstile.ts";

describe("turnstileActionOf", () => {
  test("applies Turnstile only to shorten and resolve", () => {
    assert.equal(turnstileActionOf("POST", "/api/v1/links"), "shorten");
    assert.equal(turnstileActionOf("GET", "/api/v1/links/resolve"), "resolve");
  });

  test("does not apply to health checks or short URL redirects", () => {
    assert.equal(turnstileActionOf("GET", "/api/v1/health"), null);
    assert.equal(turnstileActionOf("GET", "/abcd1234"), null);
    assert.equal(turnstileActionOf("GET", "/api/v1/links"), null);
  });
});

describe("verifyTurnstile", () => {
  const base = {
    secret: "secret",
    token: "token",
    remoteIp: "198.51.100.7",
    action: "shorten" as const,
    hostname: "s.u-na-gi.com",
  };

  // Stubs the siteverify response and records what was sent
  function stub(body: unknown, status = 200) {
    const sent: FormData[] = [];
    const fetchFn = (async (_url: unknown, init?: RequestInit) => {
      sent.push(init?.body as FormData);
      return new Response(JSON.stringify(body), { status });
    }) as typeof fetch;
    return { fetchFn, sent };
  }

  test("accepts a genuine token with matching action and host", async () => {
    const { fetchFn, sent } = stub({ success: true, action: "shorten", hostname: "s.u-na-gi.com" });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), true);
    assert.equal(sent[0]?.get("secret"), "secret");
    assert.equal(sent[0]?.get("response"), "token");
    assert.equal(sent[0]?.get("remoteip"), "198.51.100.7");
  });

  test("rejects when siteverify returns success: false", async () => {
    const { fetchFn } = stub({ success: false });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), false);
  });

  test("rejects a token from another action (the resolve form)", async () => {
    const { fetchFn } = stub({ success: true, action: "resolve", hostname: "s.u-na-gi.com" });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), false);
  });

  test("rejects a token from another host", async () => {
    const { fetchFn } = stub({ success: true, action: "shorten", hostname: "s-stg.u-na-gi.com" });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), false);
  });

  test("rejects when siteverify is unreachable or returns an error", async () => {
    const failing = (async () => {
      throw new Error("network");
    }) as unknown as typeof fetch;
    assert.equal(await verifyTurnstile({ ...base, fetchFn: failing }), false);
    assert.equal(await verifyTurnstile({ ...base, fetchFn: stub({}, 500).fetchFn }), false);
  });
});

describe("turnstileFailed", () => {
  test("returns a 403 in the same shape as API errors", async () => {
    const res = turnstileFailed();
    assert.equal(res.status, 403);
    assert.deepEqual(await res.json(), { error: "turnstile_failed" });
  });
});
