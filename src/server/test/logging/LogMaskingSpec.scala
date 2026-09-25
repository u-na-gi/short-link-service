package logging

import org.scalatestplus.play._
import play.api.libs.json.{JsNull, JsString, JsValue, Json}

class LogMaskingSpec extends PlaySpec {

  "LogMasking.mask" should {

    "keep keys and hide all values" in {
      LogMasking.mask(Json.obj("url" -> "https://example.com/?token=secret", "n" -> 1)) mustBe
        Json.obj("url" -> "***", "n" -> "***")
    }

    "hide values inside nested objects and arrays" in {
      val json = Json.obj("a" -> Json.obj("b" -> Json.arr("x", Json.obj("c" -> true))))
      LogMasking.mask(json) mustBe
        Json.obj("a" -> Json.obj("b" -> Json.arr("***", Json.obj("c" -> "***"))))
    }

    "hide null too" in {
      LogMasking.mask(Json.obj("a" -> JsNull)) mustBe Json.obj("a" -> "***")
    }

    "show revealed keys only when the value is a string, number, or boolean" in {
      val json = Json.obj(
        "error" -> "invalid_url",
        "message" -> "https://example.com/?token=secret is invalid",
        "code" -> Json.obj("nested" -> "secret")
      )
      LogMasking.mask(json, Set("error", "code")) mustBe Json.obj(
        "error" -> "invalid_url",
        "message" -> "***",
        "code" -> Json.obj("nested" -> "***")
      )
    }

    "split urls keys into parts, also when nested" in {
      val json =
        Json.obj("a" -> Json.obj("url" -> "https://example.com/p?token=secret"), "b" -> "x")
      LogMasking.mask(json, urls = Set("url")) mustBe Json.obj(
        "a" -> Json.obj(
          "url" -> Json.obj(
            "scheme" -> "https",
            "host" -> "example.com",
            "path" -> "/p",
            "query" -> Json.arr("token")
          )
        ),
        "b" -> "***"
      )
    }

    "hide urls keys when the value is not a string" in {
      LogMasking.mask(Json.obj("url" -> 1), urls = Set("url")) mustBe Json.obj("url" -> "***")
    }
  }

  "LogMasking.urlSummary" should {

    "show scheme, host, path, and query keys, but not query values" in {
      val summary = LogMasking.urlSummary(
        "https://Docs.Example.com/document/d/abc/edit?usp=sharing&token=secret&token=x"
      )
      summary mustBe Json.obj(
        "scheme" -> "https",
        "host" -> "docs.example.com",
        "path" -> "/document/d/abc/edit",
        "query" -> Json.arr("usp", "token")
      )
      summary.toString must not include "secret"
    }

    "hide the name of a query parameter without a value" in {
      val summary = LogMasking.urlSummary("https://example.com/reset?secretToken&lang=ja&other")
      (summary \ "query").as[JsValue] mustBe Json.arr("***", "lang")
      summary.toString must not include "secretToken"
    }

    "show a non-default port and omit query when there is none" in {
      LogMasking.urlSummary("http://example.com:8080/") mustBe
        Json.obj("scheme" -> "http", "host" -> "example.com", "port" -> 8080, "path" -> "/")
    }

    "omit user info and show only that a fragment exists" in {
      val summary = LogMasking.urlSummary("https://user:pass@example.com/#access_token=secret")
      summary mustBe Json.obj(
        "scheme" -> "https",
        "host" -> "example.com",
        "path" -> "/",
        "fragment" -> "***"
      )
      summary.toString must (not include "pass" and not include "secret")
    }

    "cut long paths" in {
      val path = (LogMasking.urlSummary("https://example.com/" + "a" * 1000) \ "path").as[String]
      path mustBe "/" + "a" * (LogMasking.MaxPathLength - 1) + "..."
    }

    "hide anything that cannot be read as http / https entirely" in {
      LogMasking.urlSummary("javascript:alert('secret')") mustBe JsString("***")
      LogMasking.urlSummary("example.com/?token=secret") mustBe JsString("***")
    }
  }
}
