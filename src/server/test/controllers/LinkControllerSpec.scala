package controllers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.libs.json.Json
import play.api.mvc.Cookie
import play.api.test._
import play.api.test.Helpers._

/** Starts the app and checks routing and JSON input and output. */
class LinkControllerSpec extends PlaySpec with GuiceOneAppPerTest with Injecting {

  "POST /api/v1/links" should {

    "return 201 and a short link for a valid URL" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "https://example.com"))
      val result = route(app, request).get

      status(result) mustBe CREATED
      contentType(result) mustBe Some("application/json")

      val json = contentAsJson(result)
      (json \ "originalUrl").as[String] mustBe "https://example.com/"
      (json \ "code").as[String] must have length 8
      // Built from shortener.base-url, not the Host header.
      (json \ "shortUrl").as[String] mustBe s"http://localhost:5173/${(json \ "code").as[String]}"
    }

    "return 400 invalid_request without the url field" in {
      val request = FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj())
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "invalid_request"
    }

    "return 400 invalid_request when url is not a string, without validation details" in {
      val request = FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj("url" -> 123))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj("error" -> "invalid_request")
    }

    "return 400 invalid_url (reason: empty) for an empty string" in {
      val request = FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj("url" -> ""))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj("error" -> "invalid_url", "reason" -> "empty")
    }

    "not return the input even for a broken URL" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "https://exa mple.com/?token=secret"))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj("error" -> "invalid_url", "reason" -> "malformed")
    }

    "return 400 self_reference for a URL to this service" in {
      // The host of shortener.base-url in application.conf is localhost. A different port still counts as a self-reference.
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "http://localhost:9000/abcd1234"))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "self_reference"
    }

    "return 400 invalid_url for a URL with credentials" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "https://user:pass@evil.example.com/"))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj("error" -> "invalid_url", "reason" -> "credentials")
    }

    "return 400 invalid_url for ftp" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "ftp://example.com"))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj(
        "error" -> "invalid_url",
        "reason" -> "unsupported_scheme"
      )
    }

    "return the same code when the same URL is posted twice" in {
      def post() = route(
        app,
        FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj("url" -> "https://example.com"))
      ).get

      val first = post()
      val second = post()
      status(first) mustBe CREATED
      status(second) mustBe CREATED
      (contentAsJson(second) \ "code").as[String] mustBe (contentAsJson(first) \ "code").as[String]
    }
  }

  "POST /api/v1/links (with a cookie)" should {

    "return 201 even without a CSRF token" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withCookies(Cookie("unrelated", "value"))
        .withJsonBody(Json.obj("url" -> "https://example.com"))
      val result = route(app, request).get

      status(result) mustBe CREATED
    }
  }

  "GET /api/v1/links/resolve" should {

    "return 200 and a link in the same shape for the shortUrl from create" in {
      val created = contentAsJson(
        route(
          app,
          FakeRequest(POST, "/api/v1/links")
            .withJsonBody(Json.obj("url" -> "https://www.example.org/"))
        ).get
      )
      val shortUrl = (created \ "shortUrl").as[String]

      val result = route(
        app,
        FakeRequest(GET, s"/api/v1/links/resolve?shortUrl=${URLEncoder.encode(shortUrl, UTF_8)}")
      ).get

      status(result) mustBe OK
      contentAsJson(result) mustBe created
    }

    "return 400 invalid_request without shortUrl" in {
      val result = route(app, FakeRequest(GET, "/api/v1/links/resolve")).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "invalid_request"
    }

    "return 400 not_short_url for a URL on another host" in {
      val result = route(
        app,
        FakeRequest(GET, "/api/v1/links/resolve?shortUrl=https%3A%2F%2Fother.example%2Fabcd1234")
      ).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "not_short_url"
    }

    "return 404 not_found for a short URL never issued, without the input" in {
      // shortener.base-url in application.conf is http://localhost:5173
      val result = route(
        app,
        FakeRequest(GET, "/api/v1/links/resolve?shortUrl=http%3A%2F%2Flocalhost%3A5173%2Fzzzzzzzz")
      ).get

      status(result) mustBe NOT_FOUND
      contentAsJson(result) mustBe Json.obj("error" -> "not_found")
    }
  }

  "GET /:code" should {

    "redirect to the original URL with 302 for an issued code" in {
      val created = route(
        app,
        FakeRequest(POST, "/api/v1/links")
          .withJsonBody(Json.obj("url" -> "https://www.example.org/"))
      ).get
      val code = (contentAsJson(created) \ "code").as[String]

      val result = route(app, FakeRequest(GET, s"/$code")).get

      status(result) mustBe FOUND
      redirectLocation(result) mustBe Some("https://www.example.org/")
    }

    "return 404 not_found for an unknown code, without the code" in {
      val result = route(app, FakeRequest(GET, "/zzzzzzzz")).get

      status(result) mustBe NOT_FOUND
      contentAsJson(result) mustBe Json.obj("error" -> "not_found")
    }
  }
}
