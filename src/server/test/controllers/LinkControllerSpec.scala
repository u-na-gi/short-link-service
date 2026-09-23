package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.libs.json.Json
import play.api.mvc.Cookie
import play.api.test._
import play.api.test.Helpers._

/** ルーティングと JSON の入出力を、アプリを起動して検証する。 */
class LinkControllerSpec extends PlaySpec with GuiceOneAppPerTest with Injecting {

  "POST /api/v1/links" should {

    "正しいURLで 201 と短縮リンクを返す" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "https://example.com"))
      val result = route(app, request).get

      status(result) mustBe CREATED
      contentType(result) mustBe Some("application/json")

      val json = contentAsJson(result)
      (json \ "originalUrl").as[String] mustBe "https://example.com/"
      (json \ "code").as[String] must have length 8
      // Host ヘッダではなく shortener.base-url から組み立てる。
      (json \ "shortUrl").as[String] mustBe s"http://localhost:5173/${(json \ "code").as[String]}"
    }

    "url フィールドが無ければ 400 invalid_request" in {
      val request = FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj())
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "invalid_request"
    }

    "url が文字列でなければ 400 invalid_request" in {
      val request = FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj("url" -> 123))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "invalid_request"
    }

    "空文字なら 400 invalid_url" in {
      val request = FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj("url" -> ""))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "invalid_url"
    }

    "自サービス宛なら 400 self_reference" in {
      // application.conf の shortener.base-url のホストが localhost。ポートが違っても自己参照とみなす。
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "http://localhost:9000/abcd1234"))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "self_reference"
    }

    "認証情報つきなら 400 invalid_url" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "https://user:pass@evil.example.com/"))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "invalid_url"
    }

    "ftp なら 400 invalid_url" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withJsonBody(Json.obj("url" -> "ftp://example.com"))
      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] mustBe "invalid_url"
    }

    "同じURLを 2 回投げると同じ code を返す" in {
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

  "POST /api/v1/links (Cookie 付き)" should {

    "CSRF トークンなしでも 201 を返す" in {
      val request = FakeRequest(POST, "/api/v1/links")
        .withCookies(Cookie("unrelated", "value"))
        .withJsonBody(Json.obj("url" -> "https://example.com"))
      val result = route(app, request).get

      status(result) mustBe CREATED
    }
  }

  "GET /:code" should {

    "発行済みのコードなら 302 で元URLへ飛ばす" in {
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

    "未知のコードなら 404 not_found" in {
      val result = route(app, FakeRequest(GET, "/zzzzzzzz")).get

      status(result) mustBe NOT_FOUND
      (contentAsJson(result) \ "error").as[String] mustBe "not_found"
    }
  }
}
