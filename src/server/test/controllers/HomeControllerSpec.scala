package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.test._
import play.api.test.Helpers._

/** The app is now JSON API only, so check that it returns JSON, not HTML. */
class HomeControllerSpec extends PlaySpec with GuiceOneAppPerTest with Injecting {

  "HomeController GET" should {

    "render the index page from a new instance of controller" in {
      val controller = new HomeController(stubControllerComponents())
      val home = controller.index().apply(FakeRequest(GET, "/"))

      status(home) mustBe OK
      contentType(home) mustBe Some("application/json")
      (contentAsJson(home) \ "status").as[String] mustBe "ok"
    }

    "render the index page from the application" in {
      val controller = inject[HomeController]
      val home = controller.index().apply(FakeRequest(GET, "/"))

      status(home) mustBe OK
      contentType(home) mustBe Some("application/json")
      (contentAsJson(home) \ "status").as[String] mustBe "ok"
    }

    "return ok on /api/v1/health, used through the front end" in {
      val health = route(app, FakeRequest(GET, "/api/v1/health")).get

      status(health) mustBe OK
      (contentAsJson(health) \ "status").as[String] mustBe "ok"
    }

    "render the index page from the router" in {
      val request = FakeRequest(GET, "/")
      val home = route(app, request).get

      status(home) mustBe OK
      contentType(home) mustBe Some("application/json")
      (contentAsJson(home) \ "status").as[String] mustBe "ok"
    }
  }
}
