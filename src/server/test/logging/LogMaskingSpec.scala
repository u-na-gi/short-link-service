package logging

import org.scalatestplus.play._
import play.api.libs.json.{JsNull, Json}

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
  }
}
