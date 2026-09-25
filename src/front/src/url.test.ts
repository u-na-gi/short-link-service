import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { findUrlProblem } from "./url.ts";

// Lists the same cases as the server's UrlSpec.scala to check that the front end is not stricter than the server.
describe("findUrlProblem", () => {
  test("accepts absolute http/https URLs", () => {
    assert.equal(findUrlProblem("https://example.com/"), null);
    assert.equal(findUrlProblem("http://example.com/a?b=c"), null);
  });

  test("ignores leading and trailing whitespace", () => {
    assert.equal(findUrlProblem("  https://example.com/  "), null);
  });

  test("rejects an empty string", () => {
    assert.equal(findUrlProblem(""), "empty");
    assert.equal(findUrlProblem("   "), "empty");
  });

  test("rejects schemes other than http/https", () => {
    assert.equal(findUrlProblem("ftp://example.com"), "unsupported_scheme");
    assert.equal(findUrlProblem("javascript:alert(1)"), "unsupported_scheme");
  });

  test("rejects strings without a scheme", () => {
    assert.equal(findUrlProblem("example.com"), "malformed");
    assert.equal(findUrlProblem("not a url"), "malformed");
  });

  test("rejects URLs without a host", () => {
    assert.equal(findUrlProblem("http://"), "malformed");
  });

  test("rejects URLs with credentials", () => {
    assert.equal(findUrlProblem("https://user:pass@evil.example.com/"), "credentials");
    assert.equal(findUrlProblem("https://user@evil.example.com/"), "credentials");
  });

  test("accepts Japanese domains and punycode", () => {
    assert.equal(findUrlProblem("https://例え.テスト/"), null);
    assert.equal(findUrlProblem("https://xn--r8jz45g.xn--zckzah/"), null);
    assert.equal(findUrlProblem("https://例え.テスト:8443/a?b=c#d"), null);
  });

  test("accepts uppercase schemes and hosts", () => {
    assert.equal(findUrlProblem("HTTPS://EXAMPLE.com"), null);
    assert.equal(findUrlProblem("HtTp://example.com/a"), null);
  });

  test("accepts exactly 2048 characters and rejects 2049", () => {
    const prefix = "https://example.com/";
    const atLimit = prefix + "a".repeat(2048 - prefix.length);
    assert.equal(findUrlProblem(atLimit), null);
    assert.equal(findUrlProblem(atLimit + "a"), "too_long");
  });

  test("counts the limit without leading and trailing whitespace", () => {
    const prefix = "https://example.com/";
    const atLimit = prefix + "a".repeat(2048 - prefix.length);
    assert.equal(findUrlProblem(`  ${atLimit}  `), null);
  });

  test("lets URLs that grow when normalized through, leaving them to the server", () => {
    // The server rejects it because it becomes 6320 characters after percent-encoding, but the browser does not check this.
    assert.equal(findUrlProblem("https://example.com/" + "あ".repeat(700)), null);
  });
});
