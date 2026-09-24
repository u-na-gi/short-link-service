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

  "InMemoryShortLinkRepository.saveIfAbsent (件数の上限)" should {

    "上限に達したら Full を返し、保存しない" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 2)
      repository.saveIfAbsent(ShortLink("aaaa0001", url("https://a.example"))).futureValue
      repository.saveIfAbsent(ShortLink("aaaa0002", url("https://b.example"))).futureValue

      repository.saveIfAbsent(ShortLink("aaaa0003", url("https://c.example"))).futureValue shouldBe
        SaveResult.Full
      repository.findByCode("aaaa0003").futureValue shouldBe None
      repository.findByUrl(url("https://c.example")).futureValue shouldBe None
    }

    "上限に達していても、登録済みの URL なら既存のリンクを返す" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 1)
      val first = ShortLink("aaaa0001", url("https://a.example"))
      repository.saveIfAbsent(first).futureValue

      repository.saveIfAbsent(ShortLink("aaaa0002", url("https://a.example"))).futureValue shouldBe
        SaveResult.UrlExists(first)
    }

    "保存しなかったとき (コードの被り・URL の登録済み) は件数を数えない" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 2)
      repository.saveIfAbsent(ShortLink("aaaa0001", url("https://a.example"))).futureValue
      repository.saveIfAbsent(ShortLink("aaaa0001", url("https://b.example"))).futureValue shouldBe
        SaveResult.CodeTaken
      repository.saveIfAbsent(ShortLink("aaaa0002", url("https://a.example"))).futureValue shouldBe
        SaveResult.UrlExists(ShortLink("aaaa0001", url("https://a.example")))

      // 保存したのは 1 件だけなので、まだ 1 件入る
      repository.saveIfAbsent(ShortLink("aaaa0003", url("https://c.example"))).futureValue shouldBe
        SaveResult.Saved
    }

    "並行に保存しても上限を超えない" in {
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

    "未登録なら None" in {
      new InMemoryShortLinkRepository().findByUrl(url("https://example.com")).futureValue shouldBe
        None
    }
  }
}
