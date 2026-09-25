package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._

/** Starts the app with a small count limit (shortener.max-links) and checks the response when the
  * limit is reached.
  */
class LinkCapacitySpec extends PlaySpec with GuiceOneAppPerTest {

  override def newAppForTest(testData: org.scalatest.TestData): Application =
    new GuiceApplicationBuilder().configure("shortener.max-links" -> 1).build()

  private def create(url: String) =
    route(app, FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj("url" -> url))).get

  "POST /api/v1/links (count limit)" should {

    "return 503 storage_full when the limit is reached" in {
      status(create("https://a.example")) mustBe CREATED

      val result = create("https://b.example")
      status(result) mustBe SERVICE_UNAVAILABLE
      contentAsJson(result) mustBe Json.obj("error" -> "storage_full")
    }

    "return the existing link for a registered URL even at the limit" in {
      val first = contentAsJson(create("https://a.example"))

      val again = create("https://a.example")
      status(again) mustBe CREATED
      (contentAsJson(again) \ "code").as[String] mustBe (first \ "code").as[String]
    }
  }

  "shortener.max-links" should {

    // In test mode Guice creates singletons lazily, so check the failure when it is fetched.
    // In prod mode (Guice's PRODUCTION stage) it is created at startup, so startup stops
    "not create LinkCapacity when 0 or less (startup stops in production)" in {
      val built = new GuiceApplicationBuilder().configure("shortener.max-links" -> 0).build()
      val error = intercept[Exception] {
        built.injector.instanceOf[infra.inmemory.LinkCapacity]
      }
      Iterator
        .iterate[Throwable](error)(_.getCause)
        .takeWhile(_ != null)
        .exists(e => Option(e.getMessage).exists(_.contains("must be positive"))) mustBe true
    }
  }
}
