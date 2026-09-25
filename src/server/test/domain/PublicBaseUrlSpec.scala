package domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Pure unit test that does not start Play. */
class PublicBaseUrlSpec extends AnyWordSpec with Matchers {

  "PublicBaseUrl.from" should {

    "accept a URL with only scheme, host, and port" in {
      PublicBaseUrl.from("https://example.com").map(_.value) shouldBe Right("https://example.com")
      PublicBaseUrl.from("http://localhost:5173").map(_.value) shouldBe Right(
        "http://localhost:5173"
      )
    }

    "drop the trailing slash and lowercase the host" in {
      PublicBaseUrl.from(" https://Example.COM/ ").map(_.value) shouldBe Right(
        "https://example.com"
      )
    }

    "reject schemes other than http/https" in {
      PublicBaseUrl.from("ftp://example.com") shouldBe Left(
        PublicBaseUrl.Error.UnsupportedScheme(Some("ftp"))
      )
      PublicBaseUrl.from("example.com") shouldBe Left(PublicBaseUrl.Error.UnsupportedScheme(None))
    }

    "reject a URL without a host name" in {
      PublicBaseUrl.from("https:///") shouldBe Left(PublicBaseUrl.Error.MissingHost)
    }

    "reject a path, query, fragment, or credentials" in {
      PublicBaseUrl.from("https://example.com/s") shouldBe Left(PublicBaseUrl.Error.HasPath("/s"))
      PublicBaseUrl.from("https://example.com?a=b") shouldBe Left(PublicBaseUrl.Error.HasQuery)
      PublicBaseUrl.from("https://example.com#top") shouldBe Left(PublicBaseUrl.Error.HasFragment)
      PublicBaseUrl.from("https://user:pass@example.com") shouldBe Left(
        PublicBaseUrl.Error.HasUserInfo
      )
    }

    "return the parse exception for a broken URL" in {
      PublicBaseUrl.from("https://exa mple.com") should matchPattern {
        case Left(PublicBaseUrl.Error.Malformed(_)) =>
      }
    }
  }

  "linkTo" should {
    "return a short URL with the code as the path" in {
      PublicBaseUrl.from("https://example.com").map(_.linkTo("Xk3pR8vN")) shouldBe Right(
        "https://example.com/Xk3pR8vN"
      )
    }
  }

  "codeOf" should {
    val base = PublicBaseUrl.from("https://example.com").toOption.get

    "extract the code from a short URL of this service" in {
      base.codeOf("https://example.com/Xk3pR8vN") shouldBe Some("Xk3pR8vN")
    }

    "ignore surrounding whitespace, uppercase host, and different scheme or port" in {
      base.codeOf(" https://EXAMPLE.com/Xk3pR8vN ") shouldBe Some("Xk3pR8vN")
      base.codeOf("http://example.com:8080/Xk3pR8vN") shouldBe Some("Xk3pR8vN")
    }

    "ignore the query and fragment" in {
      base.codeOf("https://example.com/Xk3pR8vN?utm_source=x#top") shouldBe Some("Xk3pR8vN")
    }

    "keep the case of the code" in {
      base.codeOf("https://example.com/XK3PR8VN") shouldBe Some("XK3PR8VN")
    }

    "return None for another host" in {
      base.codeOf("https://other.example/Xk3pR8vN") shouldBe None
      base.codeOf("https://sub.example.com/Xk3pR8vN") shouldBe None
    }

    "return None unless the path is a single segment of 8 alphanumeric characters" in {
      base.codeOf("https://example.com/") shouldBe None
      base.codeOf("https://example.com/qEmT9gT") shouldBe None
      base.codeOf("https://example.com/Xk3pR8vNX") shouldBe None
      base.codeOf("https://example.com/qEmT9gT-") shouldBe None
      base.codeOf("https://example.com/api/v1/links") shouldBe None
      base.codeOf("https://example.com/Xk3pR8vN/") shouldBe None
    }

    "return None when it cannot be read as a URL" in {
      base.codeOf("") shouldBe None
      base.codeOf("example.com/Xk3pR8vN") shouldBe None
      base.codeOf("Xk3pR8vN") shouldBe None
    }
  }

  "host" should {
    "return the host name without the port" in {
      PublicBaseUrl.from("http://localhost:5173").map(_.host) shouldBe Right(
        ServiceHost("localhost")
      )
    }
  }
}
