package logging

import net.logstash.logback.marker.Markers
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsValue, Json}
import play.api.test._
import play.api.test.Helpers._
import support.LogCapture

/** アプリを起動してリクエストを流し、access ロガーに出た JSON を検証する。 */
class AccessLogFilterSpec extends PlaySpec with GuiceOneAppPerTest {

  private def captureAccessLog(run: => Unit): Seq[JsValue] = LogCapture.capture("access")(run)._2

  "AccessLogFilter" should {

    "request body は値を隠し、レスポンスは error と code だけ値を出す" in {
      val (result, logs) = LogCapture.capture("access") {
        val result = route(
          app,
          FakeRequest(POST, "/api/v1/links")
            .withHeaders("X-Forwarded-Host" -> "short.example")
            .withJsonBody(Json.obj("url" -> "https://example.com/?token=secret"))
        ).get
        status(result) mustBe CREATED
        result
      }

      logs must have size 1
      val log = logs.head
      (log \ "requestId").as[String] mustBe header(RequestId.Header, result).value
      (log \ "method").as[String] mustBe "POST"
      (log \ "host").as[String] mustBe "short.example"
      (log \ "path").as[String] mustBe "/api/v1/links"
      (log \ "status").as[Int] mustBe CREATED
      (log \ "requestBody").as[JsValue] mustBe Json.obj("url" -> "***")
      (log \ "responseBody" \ "code").as[String] must have length 8
      (log \ "responseBody" \ "originalUrl").as[String] mustBe "***"
      (log \ "responseBody" \ "shortUrl").as[String] mustBe "***"
      log.toString must not include "secret"
    }

    "業務エラーは error の値が出て、クエリの値は隠す" in {
      val logs = captureAccessLog {
        val result = route(
          app,
          FakeRequest(GET, "/api/v1/links/resolve?shortUrl=https%3A%2F%2Fother.example%2Fsecret00")
        ).get
        status(result) mustBe BAD_REQUEST
      }

      val log = logs.head
      (log \ "query").as[JsValue] mustBe Json.obj("shortUrl" -> "***")
      (log \ "responseBody" \ "error").as[String] mustBe "not_short_url"
      log.toString must not include "secret00"
    }

    "JSON として読めない body は中身を出さずサイズだけ出す" in {
      val logs = captureAccessLog {
        val result = route(
          app,
          FakeRequest(POST, "/api/v1/links")
            .withHeaders(CONTENT_TYPE -> JSON)
            .withBody("""{"url": "https://example.com/?token=secret" """)
        ).get
        status(result) mustBe BAD_REQUEST
        (contentAsJson(result) \ "error").as[String] mustBe "invalid_request"
      }

      val log = logs.head
      (log \ "requestBody").as[String] must include("not valid JSON")
      log.toString must not include "secret"
    }

    "ヘルスチェックは INFO では出さない" in {
      val logs = captureAccessLog {
        status(route(app, FakeRequest(GET, "/api/v1/health")).get) mustBe OK
      }

      logs mustBe empty
    }

    "URL を持つ項目をそのままログに渡しても、logback.xml の保険で隠れる" in {
      val logs = captureAccessLog {
        LoggerFactory
          .getLogger("access")
          .info(
            Markers.append(
              "nested",
              java.util.Map.of("originalUrl", "https://example.com/?token=secret")
            ),
            "うっかり"
          )
      }

      (logs.head \ "nested" \ "originalUrl").as[String] mustBe "***"
    }
  }
}
