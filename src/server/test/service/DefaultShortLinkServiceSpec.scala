package service

import domain.{ShortLink, ShortLinkService, Url}
import infra.inmemory.InMemoryShortLinkRepository
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.ExecutionContext
import support.SequenceCodes

class DefaultShortLinkServiceSpec extends AnyWordSpec with Matchers with ScalaFutures {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private def url(raw: String): Url = Url.from(raw).toOption.get

  "DefaultShortLinkService.randomCode" should {

    "英大文字・小文字・数字だけの 8 文字のコードを振る" in {
      (1 to 1000).foreach { _ =>
        DefaultShortLinkService.randomCode() should fullyMatch regex "[A-Za-z0-9]{8}"
      }
    }
  }

  "DefaultShortLinkService.issue" should {

    "振ったコードで保存して返す" in {
      val repository = new InMemoryShortLinkRepository()
      val service = new DefaultShortLinkService(repository, new SequenceCodes("fixed123"))

      service.issue(url("https://example.com")).futureValue shouldBe
        Right(ShortLink("fixed123", url("https://example.com")))
      repository.findByCode("fixed123").futureValue.map(_.url.value) shouldBe
        Some("https://example.com/")
    }

    "既定のコンストラクタでは乱数のコードを振る" in {
      val service = new DefaultShortLinkService(new InMemoryShortLinkRepository())
      service.issue(url("https://example.com")).futureValue.map(_.code).toOption.get should
        fullyMatch regex "[A-Za-z0-9]{8}"
    }

    "コードが被ったら採番し直す" in {
      val repository = new InMemoryShortLinkRepository()
      repository.saveIfAbsent(ShortLink("fixed123", url("https://taken.example"))).futureValue

      val codes = new SequenceCodes("fixed123", "fixed123", "fresh001")
      val result = new DefaultShortLinkService(repository, codes)
        .issue(url("https://example.com"))
        .futureValue

      result.map(_.code) shouldBe Right("fresh001")
      codes.calls shouldBe 3
      repository.findByCode("fresh001").futureValue.map(_.url.value) shouldBe
        Some("https://example.com/")
      // 被った側のリンクは上書きされない。
      repository.findByCode("fixed123").futureValue.map(_.url.value) shouldBe
        Some("https://taken.example/")
    }

    "被り続けたら上限回数で CodeExhausted を返す" in {
      val repository = new InMemoryShortLinkRepository()
      repository.saveIfAbsent(ShortLink("fixed123", url("https://taken.example"))).futureValue

      val codes = new SequenceCodes("fixed123")
      new DefaultShortLinkService(repository, codes)
        .issue(url("https://example.com"))
        .futureValue shouldBe Left(ShortLinkService.Error.CodeExhausted)
      codes.calls shouldBe DefaultShortLinkService.MaxAttempts
    }

    "並行リクエストに先を越されていたら既存のリンクを返す" in {
      val repository = new InMemoryShortLinkRepository()
      val existing = ShortLink("first001", url("https://example.com"))
      repository.saveIfAbsent(existing).futureValue

      new DefaultShortLinkService(repository, new SequenceCodes("second02"))
        .issue(url("https://example.com"))
        .futureValue shouldBe Right(existing)
      repository.findByCode("second02").futureValue shouldBe None
    }
  }
}
