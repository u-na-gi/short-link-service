package infra.inmemory

import domain.{SaveResult, ShortLink, Url}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class InMemoryShortLinkRepositorySpec extends AnyWordSpec with Matchers with ScalaFutures {

  private def url(raw: String): Url = Url.from(raw).toOption.get

  "InMemoryShortLinkRepository.saveIfAbsent" should {

    "save so the link can be found by both code and URL when not registered" in {
      val repository = new InMemoryShortLinkRepository()
      val link = ShortLink("abcd1234", url("https://example.com"))

      repository.saveIfAbsent(link).futureValue shouldBe SaveResult.Saved
      repository.findByCode("abcd1234").futureValue shouldBe Some(link)
      repository.findByUrl(url("https://example.com")).futureValue shouldBe Some(link)
    }

    "return CodeTaken without overwriting when the code is used" in {
      val repository = new InMemoryShortLinkRepository()
      val first = ShortLink("abcd1234", url("https://a.example"))
      repository.saveIfAbsent(first).futureValue

      repository.saveIfAbsent(ShortLink("abcd1234", url("https://b.example"))).futureValue shouldBe
        SaveResult.CodeTaken
      repository.findByCode("abcd1234").futureValue shouldBe Some(first)
      repository.findByUrl(url("https://b.example")).futureValue shouldBe None
    }

    "return the existing link as UrlExists when the URL is registered" in {
      val repository = new InMemoryShortLinkRepository()
      val first = ShortLink("abcd1234", url("https://example.com"))
      repository.saveIfAbsent(first).futureValue

      repository
        .saveIfAbsent(ShortLink("efgh5678", url("https://example.com")))
        .futureValue shouldBe
        SaveResult.UrlExists(first)
      repository.findByCode("efgh5678").futureValue shouldBe None
    }
  }

  "InMemoryShortLinkRepository.saveIfAbsent (count limit)" should {

    "return Full and not save when the limit is reached" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 2)
      repository.saveIfAbsent(ShortLink("aaaa0001", url("https://a.example"))).futureValue
      repository.saveIfAbsent(ShortLink("aaaa0002", url("https://b.example"))).futureValue

      repository.saveIfAbsent(ShortLink("aaaa0003", url("https://c.example"))).futureValue shouldBe
        SaveResult.Full
      repository.findByCode("aaaa0003").futureValue shouldBe None
      repository.findByUrl(url("https://c.example")).futureValue shouldBe None
    }

    "return the existing link for a registered URL even at the limit" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 1)
      val first = ShortLink("aaaa0001", url("https://a.example"))
      repository.saveIfAbsent(first).futureValue

      repository.saveIfAbsent(ShortLink("aaaa0002", url("https://a.example"))).futureValue shouldBe
        SaveResult.UrlExists(first)
    }

    "not count links that were not saved (code taken, URL registered)" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 2)
      repository.saveIfAbsent(ShortLink("aaaa0001", url("https://a.example"))).futureValue
      repository.saveIfAbsent(ShortLink("aaaa0001", url("https://b.example"))).futureValue shouldBe
        SaveResult.CodeTaken
      repository.saveIfAbsent(ShortLink("aaaa0002", url("https://a.example"))).futureValue shouldBe
        SaveResult.UrlExists(ShortLink("aaaa0001", url("https://a.example")))

      // Only 1 link was saved, so there is room for 1 more
      repository.saveIfAbsent(ShortLink("aaaa0003", url("https://c.example"))).futureValue shouldBe
        SaveResult.Saved
    }

    "not go over the limit with concurrent saves" in {
      import scala.concurrent.{ExecutionContext, Future}
      given ExecutionContext = ExecutionContext.global
      val repository = new InMemoryShortLinkRepository(maxLinks = 50)

      val results = Future
        .traverse((1 to 200).toList) { i =>
          Future(f"c$i%07d").flatMap { code =>
            repository.saveIfAbsent(ShortLink(code, url(s"https://example.com/$i")))
          }
        }
        .futureValue

      results.count(_ == SaveResult.Saved) shouldBe 50
      results.count(_ == SaveResult.Full) shouldBe 150
    }
  }

  "InMemoryShortLinkRepository.findByUrl" should {

    "return None when not registered" in {
      new InMemoryShortLinkRepository().findByUrl(url("https://example.com")).futureValue shouldBe
        None
    }
  }
}
