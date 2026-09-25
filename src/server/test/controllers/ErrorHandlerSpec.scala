package controllers

import org.scalatestplus.play._
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._

class ErrorHandlerSpec extends PlaySpec {

  "ErrorHandler" should {

    "return 500 internal_error for an unhandled exception without the exception message" in {
      val result =
        new ErrorHandler().onServerError(FakeRequest(), new RuntimeException("internal details"))

      status(result) mustBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "error").as[String] mustBe "internal_error"
      contentAsJson(result) mustBe Json.obj("error" -> "internal_error")
    }

    "return a JSON code matching the status for a client error, without Play's message" in {
      val result = new ErrorHandler().onClientError(FakeRequest(), NOT_FOUND, "no route")

      status(result) mustBe NOT_FOUND
      contentType(result) mustBe Some("application/json")
      contentAsJson(result) mustBe Json.obj("error" -> "not_found")
    }
  }
}
