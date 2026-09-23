import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { findUrlProblem } from "./url.ts";

// サーバの UrlSpec.scala と同じケースを並べ、フロントがサーバより厳しくなっていないことを確かめる。
describe("findUrlProblem", () => {
  test("http/https の絶対URLを受け入れる", () => {
    assert.equal(findUrlProblem("https://example.com/"), null);
    assert.equal(findUrlProblem("http://example.com/a?b=c"), null);
  });

  test("前後の空白は無視する", () => {
    assert.equal(findUrlProblem("  https://example.com/  "), null);
  });

  test("空文字を拒否する", () => {
    assert.equal(findUrlProblem(""), "empty");
    assert.equal(findUrlProblem("   "), "empty");
  });

  test("http/https 以外のスキームを拒否する", () => {
    assert.equal(findUrlProblem("ftp://example.com"), "unsupported_scheme");
    assert.equal(findUrlProblem("javascript:alert(1)"), "unsupported_scheme");
  });

  test("スキームの無い文字列を拒否する", () => {
    assert.equal(findUrlProblem("example.com"), "malformed");
    assert.equal(findUrlProblem("not a url"), "malformed");
  });

  test("ホストの無いURLを拒否する", () => {
    assert.equal(findUrlProblem("http://"), "malformed");
  });

  test("認証情報つきの url を拒否する", () => {
    assert.equal(findUrlProblem("https://user:pass@evil.example.com/"), "credentials");
    assert.equal(findUrlProblem("https://user@evil.example.com/"), "credentials");
  });

  test("日本語ドメインと punycode を受け入れる", () => {
    assert.equal(findUrlProblem("https://例え.テスト/"), null);
    assert.equal(findUrlProblem("https://xn--r8jz45g.xn--zckzah/"), null);
    assert.equal(findUrlProblem("https://例え.テスト:8443/a?b=c#d"), null);
  });

  test("大文字のスキームとホストを受け入れる", () => {
    assert.equal(findUrlProblem("HTTPS://EXAMPLE.com"), null);
    assert.equal(findUrlProblem("HtTp://example.com/a"), null);
  });

  test("ちょうど上限の 2048 文字は受け入れ、2049 文字は拒否する", () => {
    const prefix = "https://example.com/";
    const atLimit = prefix + "a".repeat(2048 - prefix.length);
    assert.equal(findUrlProblem(atLimit), null);
    assert.equal(findUrlProblem(atLimit + "a"), "too_long");
  });

  test("上限は前後の空白を除いた長さで数える", () => {
    const prefix = "https://example.com/";
    const atLimit = prefix + "a".repeat(2048 - prefix.length);
    assert.equal(findUrlProblem(`  ${atLimit}  `), null);
  });

  test("正規化で伸びる URL はサーバに任せて通す", () => {
    // サーバではパーセントエンコード後に 6320 文字になって拒否されるが、ブラウザ側では判定しない。
    assert.equal(findUrlProblem("https://example.com/" + "あ".repeat(700)), null);
  });
});
