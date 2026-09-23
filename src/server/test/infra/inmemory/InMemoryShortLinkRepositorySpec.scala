package infra.inmemory

import domain.{SaveResult, ShortLink, Url}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class InMemoryShortLinkRepositorySpec extends AnyWordSpec with Matchers with ScalaFutures {

  private def url(raw: String): Url = Url.from(raw).toOption.get

  "InMemoryShortLinkRepository.saveIfAbsent" should {

    "未登録ならコードと URL の両方で引けるように保存する" in {
      val repository = new InMemoryShortLinkRepository()
      val link = ShortLink("abcd1234", url("https://example.com"))

      repository.saveIfAbsent(link).futureValue shouldBe SaveResult.Saved
      repository.findByCode("abcd1234").futureValue shouldBe Some(link)
      repository.findByUrl(url("https://example.com")).futureValue shouldBe Some(link)
    }

    "コードが使用済みなら CodeTaken を返し、上書きしない" in {
      val repository = new InMemoryShortLinkRepository()
      val first = ShortLink("abcd1234", url("https://a.example"))
      repository.saveIfAbsent(first).futureValue

      repository.saveIfAbsent(ShortLink("abcd1234", url("https://b.example"))).futureValue shouldBe
        SaveResult.CodeTaken
      repository.findByCode("abcd1234").futureValue shouldBe Some(first)
      repository.findByUrl(url("https://b.example")).futureValue shouldBe None
    }

    "URL が登録済みなら既存のリンクを UrlExists で返す" in {
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

  "InMemoryShortLinkRepository.findByUrl" should {

    "未登録なら None" in {
      new InMemoryShortLinkRepository().findByUrl(url("https://example.com")).futureValue shouldBe
        None
    }
  }
}
