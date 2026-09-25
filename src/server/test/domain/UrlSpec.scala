package domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Pure unit test that does not start Play. */
class UrlSpec extends AnyWordSpec with Matchers {

  "Url.from" should {

    "accept absolute http/https URLs" in {
      Url.from("https://example.com/").map(_.value) shouldBe Right("https://example.com/")
      Url.from("http://example.com/a?b=c").map(_.value) shouldBe Right("http://example.com/a?b=c")
    }

    "trim surrounding whitespace" in {
      Url.from("  https://example.com/  ").map(_.value) shouldBe Right("https://example.com/")
    }

    "reject an empty string" in {
      Url.from("") shouldBe Left(Url.Error.Empty)
      Url.from("   ") shouldBe Left(Url.Error.Empty)
    }

    "reject schemes other than http/https" in {
      Url.from("ftp://example.com") shouldBe Left(Url.Error.UnsupportedScheme("ftp"))
      Url.from("javascript:alert(1)") shouldBe Left(Url.Error.UnsupportedScheme("javascript"))
    }

    "reject a string without a scheme" in {
      Url.from("example.com").isLeft shouldBe true
      Url.from("not a url").isLeft shouldBe true
    }

    "reject a URL without a host" in {
      Url.from("http://").isLeft shouldBe true
    }

    "reject a url with credentials" in {
      Url.from("https://user:pass@evil.example.com/") shouldBe Left(Url.Error.ContainsCredentials)
      Url.from("https://user@evil.example.com/") shouldBe Left(Url.Error.ContainsCredentials)
    }

    "accept a Japanese domain normalized to punycode" in {
      Url.from("https://例え.テスト/").map(_.value) shouldBe
        Right("https://xn--r8jz45g.xn--zckzah/")
    }

    "accept punycode input as-is" in {
      Url.from("https://xn--r8jz45g.xn--zckzah/").map(_.value) shouldBe
        Right("https://xn--r8jz45g.xn--zckzah/")
    }

    "normalize while keeping the port and path" in {
      Url.from("https://例え.テスト:8443/a?b=c#d").map(_.value) shouldBe
        Right("https://xn--r8jz45g.xn--zckzah:8443/a?b=c#d")
    }

    "add / for an empty path and lowercase the host" in {
      Url.from("https://EXAMPLE.com").map(_.value) shouldBe Right("https://example.com/")
    }

    "lowercase the scheme" in {
      Url.from("HTTPS://example.com").map(_.value) shouldBe Right("https://example.com/")
      Url.from("HtTp://example.com/a").map(_.value) shouldBe Right("http://example.com/a")
    }

    "expose the host in lowercase" in {
      Url.from("https://EXAMPLE.com/x").map(_.host) shouldBe Right("example.com")
    }

    "reject a URL that is too long" in {
      val tooLong = "https://example.com/" + "a" * 2048
      Url.from(tooLong) shouldBe Left(Url.Error.TooLong(tooLong.length))
    }

    "accept exactly 2048 characters and reject 2049" in {
      val prefix = "https://example.com/"
      val atLimit = prefix + "a" * (2048 - prefix.length)
      val overLimit = atLimit + "a"
      Url.from(atLimit).map(_.value) shouldBe Right(atLimit)
      Url.from(overLimit) shouldBe Left(Url.Error.TooLong(2049))
    }

    "reject a URL that goes over the limit after normalization" in {
      // The input is 720 characters, but "あ" is encoded as "%E3%81%82" (9 characters), making 6320.
      val raw = "https://example.com/" + "あ" * 700
      raw.length should be <= 2048
      Url.from(raw) shouldBe Left(Url.Error.TooLong(6320))
    }
  }
}
