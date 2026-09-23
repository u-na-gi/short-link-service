package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.libs.json.Json
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
      (json \ "shortUrl").as[String] must endWith((json \ "code").as[String])
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
      // FakeRequest のホストと application.conf の shortener.host がどちらも localhost。
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
  }
}
