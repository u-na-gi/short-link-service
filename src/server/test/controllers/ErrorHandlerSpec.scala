package controllers

import org.scalatestplus.play._
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._

class ErrorHandlerSpec extends PlaySpec {

  "ErrorHandler" should {

    "未処理の例外は 500 internal_error で、例外のメッセージは返さない" in {
      val result = new ErrorHandler().onServerError(FakeRequest(), new RuntimeException("内部の事情"))

      status(result) mustBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "error").as[String] mustBe "internal_error"
      contentAsJson(result) mustBe Json.obj("error" -> "internal_error")
    }

    "クライアントエラーはステータスに応じたコードを JSON で返し、Play のメッセージは返さない" in {
      val result = new ErrorHandler().onClientError(FakeRequest(), NOT_FOUND, "no route")

      status(result) mustBe NOT_FOUND
      contentType(result) mustBe Some("application/json")
      contentAsJson(result) mustBe Json.obj("error" -> "not_found")
    }
  }
}
