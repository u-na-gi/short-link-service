package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._

/** 件数の上限 (shortener.max-links) を小さくしてアプリを起動し、上限に達したときの応答を見る。 */
class LinkCapacitySpec extends PlaySpec with GuiceOneAppPerTest {

  override def newAppForTest(testData: org.scalatest.TestData): Application =
    new GuiceApplicationBuilder().configure("shortener.max-links" -> 1).build()

  private def create(url: String) =
    route(app, FakeRequest(POST, "/api/v1/links").withJsonBody(Json.obj("url" -> url))).get

  "POST /api/v1/links (件数の上限)" should {

    "上限に達したら 503 storage_full を返す" in {
      status(create("https://a.example")) mustBe CREATED

      val result = create("https://b.example")
      status(result) mustBe SERVICE_UNAVAILABLE
      contentAsJson(result) mustBe Json.obj("error" -> "storage_full")
    }

    "上限に達していても、登録済みの URL なら既存のリンクを返す" in {
      val first = contentAsJson(create("https://a.example"))

      val again = create("https://a.example")
      status(again) mustBe CREATED
      (contentAsJson(again) \ "code").as[String] mustBe (first \ "code").as[String]
    }
  }

  "shortener.max-links" should {

    // テストモードの Guice はシングルトンを遅延生成するので、取り出した時点の失敗を見る。
    // 本番モード (Guice の PRODUCTION ステージ) では起動時に生成されるので、起動が止まる
    "0 以下なら LinkCapacity を作れない (本番では起動が止まる)" in {
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
