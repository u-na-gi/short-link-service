package logging

import org.scalatestplus.play._
import play.api.libs.json.{JsNull, JsString, JsValue, Json}

class LogMaskingSpec extends PlaySpec {

  "LogMasking.mask" should {

    "キーを残して値をすべて隠す" in {
      LogMasking.mask(Json.obj("url" -> "https://example.com/?token=secret", "n" -> 1)) mustBe
        Json.obj("url" -> "***", "n" -> "***")
    }

    "入れ子のオブジェクトや配列の中まで隠す" in {
      val json = Json.obj("a" -> Json.obj("b" -> Json.arr("x", Json.obj("c" -> true))))
      LogMasking.mask(json) mustBe
        Json.obj("a" -> Json.obj("b" -> Json.arr("***", Json.obj("c" -> "***"))))
    }

    "null も隠す" in {
      LogMasking.mask(Json.obj("a" -> JsNull)) mustBe Json.obj("a" -> "***")
    }

    "revealed のキーは値が文字列・数値・真偽値のときだけ出す" in {
      val json = Json.obj(
        "error" -> "invalid_url",
        "message" -> "https://example.com/?token=secret は不正です",
        "code" -> Json.obj("nested" -> "secret")
      )
      LogMasking.mask(json, Set("error", "code")) mustBe Json.obj(
        "error" -> "invalid_url",
        "message" -> "***",
        "code" -> Json.obj("nested" -> "***")
      )
    }

    "urls のキーは部品に分けて出し、入れ子の中でも効く" in {
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

    "urls のキーでも文字列でなければ隠す" in {
      LogMasking.mask(Json.obj("url" -> 1), urls = Set("url")) mustBe Json.obj("url" -> "***")
    }
  }

  "LogMasking.urlSummary" should {

    "スキーム・ホスト・パス・クエリのキーを出し、クエリの値は出さない" in {
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

    "値の無いクエリパラメータは名前も隠す" in {
      val summary = LogMasking.urlSummary("https://example.com/reset?secretToken&lang=ja&other")
      (summary \ "query").as[JsValue] mustBe Json.arr("***", "lang")
      summary.toString must not include "secretToken"
    }

    "既定以外のポートは出し、クエリが無ければ query を出さない" in {
      LogMasking.urlSummary("http://example.com:8080/") mustBe
        Json.obj("scheme" -> "http", "host" -> "example.com", "port" -> 8080, "path" -> "/")
    }

    "ユーザー情報は出さず、フラグメントはあることだけ出す" in {
      val summary = LogMasking.urlSummary("https://user:pass@example.com/#access_token=secret")
      summary mustBe Json.obj(
        "scheme" -> "https",
        "host" -> "example.com",
        "path" -> "/",
        "fragment" -> "***"
      )
      summary.toString must (not include "pass" and not include "secret")
    }

    "長いパスは切る" in {
      val path = (LogMasking.urlSummary("https://example.com/" + "a" * 1000) \ "path").as[String]
      path mustBe "/" + "a" * (LogMasking.MaxPathLength - 1) + "..."
    }

    "http / https として読めないものは丸ごと隠す" in {
      LogMasking.urlSummary("javascript:alert('secret')") mustBe JsString("***")
      LogMasking.urlSummary("example.com/?token=secret") mustBe JsString("***")
    }
  }
}
