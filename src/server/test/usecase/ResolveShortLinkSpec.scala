package usecase

import domain.{ShortLink, Url}
import infra.inmemory.InMemoryShortLinkRepository
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ResolveShortLinkSpec extends AnyWordSpec with Matchers with ScalaFutures {

  "ResolveShortLink.execute" should {

    "発行済みのコードならリンクを返す" in {
      val repository = new InMemoryShortLinkRepository()
      val link = ShortLink("abcd1234", Url.from("https://example.com").toOption.get)
      repository.saveIfAbsent(link).futureValue

      new ResolveShortLink(repository).execute("abcd1234").futureValue shouldBe Some(link)
    }

    "未知のコードなら None" in {
      new ResolveShortLink(new InMemoryShortLinkRepository())
        .execute("abcd1234")
        .futureValue shouldBe
        None
    }
  }
}
