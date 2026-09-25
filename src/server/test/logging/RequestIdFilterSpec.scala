package logging

import org.apache.pekko.stream.Materializer
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.mvc.{AnyContent, DefaultActionBuilder, Request, Result}
import play.api.test._
import play.api.test.Helpers._
import scala.concurrent.Future
import support.LogCapture

class RequestIdFilterSpec extends PlaySpec with GuiceOneAppPerTest {

  "RequestIdFilter" should {

    "assign a different UUID to each request and return it in X-Request-Id" in {
      def requestId() =
        header(RequestId.Header, route(app, FakeRequest(GET, "/api/v1/health")).get).value

      val first = requestId()
      val second = requestId()
      first must fullyMatch regex "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
      first must not be second
    }

    "not use an incoming X-Request-Id" in {
      val result = route(
        app,
        FakeRequest(GET, "/api/v1/health").withHeaders(RequestId.Header -> "spoofed")
      ).get

      header(RequestId.Header, result).value must not be "spoofed"
    }

    "attach the same ID to the stack trace log and the response when the action throws" in {
      given Materializer = app.materializer
      val actionBuilder = app.injector.instanceOf[DefaultActionBuilder]
      val action = actionBuilder { (_: Request[AnyContent]) => throw new RuntimeException("boom") }
      val filtered = app.injector.instanceOf[RequestIdFilter].apply(action)

      val (result, logs) = LogCapture.capture("controllers.ErrorHandler") {
        val result: Future[Result] = call(filtered, FakeRequest(GET, "/boom"))
        status(result) mustBe INTERNAL_SERVER_ERROR
        result
      }

      val id = header(RequestId.Header, result).value
      (contentAsJson(result) \ "error").as[String] mustBe "internal_error"
      logs must have size 1
      (logs.head \ "requestId").as[String] mustBe id
      (logs.head \ "stack_trace").as[String] must include("boom")
    }
  }
}
