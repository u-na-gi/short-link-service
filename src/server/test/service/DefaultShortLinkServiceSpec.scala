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

    "generate 8-character codes of only uppercase letters, lowercase letters, and digits" in {
      (1 to 1000).foreach { _ =>
        DefaultShortLinkService.randomCode() should fullyMatch regex "[A-Za-z0-9]{8}"
      }
    }
  }

  "DefaultShortLinkService.issue" should {

    "save and return the link with the generated code" in {
      val repository = new InMemoryShortLinkRepository()
      val service = new DefaultShortLinkService(repository, new SequenceCodes("fixed123"))

      service.issue(url("https://example.com")).futureValue shouldBe
        Right(ShortLink("fixed123", url("https://example.com")))
      repository.findByCode("fixed123").futureValue.map(_.url.value) shouldBe
        Some("https://example.com/")
    }

    "generate random codes with the default constructor" in {
      val service = new DefaultShortLinkService(new InMemoryShortLinkRepository())
      service.issue(url("https://example.com")).futureValue.map(_.code).toOption.get should
        fullyMatch regex "[A-Za-z0-9]{8}"
    }

    "generate a new code on collision" in {
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
      // The link that already had the code is not overwritten.
      repository.findByCode("fixed123").futureValue.map(_.url.value) shouldBe
        Some("https://taken.example/")
    }

    "return CodeExhausted after the maximum attempts when collisions continue" in {
      val repository = new InMemoryShortLinkRepository()
      repository.saveIfAbsent(ShortLink("fixed123", url("https://taken.example"))).futureValue

      val codes = new SequenceCodes("fixed123")
      new DefaultShortLinkService(repository, codes)
        .issue(url("https://example.com"))
        .futureValue shouldBe Left(ShortLinkService.Error.CodeExhausted)
      codes.calls shouldBe DefaultShortLinkService.MaxAttempts
    }

    "return StorageFull without retrying when the count limit is reached" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 1)
      repository.saveIfAbsent(ShortLink("fixed123", url("https://taken.example"))).futureValue

      val codes = new SequenceCodes("fresh001")
      new DefaultShortLinkService(repository, codes)
        .issue(url("https://example.com"))
        .futureValue shouldBe Left(ShortLinkService.Error.StorageFull)
      codes.calls shouldBe 1
    }

    "return the existing link when a concurrent request got there first" in {
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
