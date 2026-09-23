package usecase

import domain.{PublicBaseUrl, ShortLink, Url}
import infra.inmemory.InMemoryShortLinkRepository
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.ExecutionContext

class ResolveShortLinkSpec extends AnyWordSpec with Matchers with ScalaFutures {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private val baseUrl = PublicBaseUrl.from("https://example.com").toOption.get

  private val link = ShortLink("abcd1234", Url.from("https://www.example.org").toOption.get)

  /** link を登録済みのリポジトリで組み立てる。 */
  private def newUsecase() = {
    val repository = new InMemoryShortLinkRepository()
    repository.saveIfAbsent(link).futureValue
    new ResolveShortLink(repository, baseUrl)
  }

  "ResolveShortLink.execute" should {

    "発行済みのコードならリンクを返す" in {
      newUsecase().execute("abcd1234").futureValue shouldBe Some(link)
    }

    "未知のコードなら None" in {
      newUsecase().execute("zzzzzzzz").futureValue shouldBe None
    }
  }

  "ResolveShortLink.fromShortUrl" should {

    "発行済みの短縮URLならリンクを返す" in {
      newUsecase().fromShortUrl("https://example.com/abcd1234").futureValue shouldBe Right(link)
    }

    "形は正しいが未発行なら NotFound" in {
      newUsecase().fromShortUrl("https://example.com/zzzzzzzz").futureValue shouldBe
        Left(ResolveShortLinkError.NotFound)
    }

    "他ホストの URL なら NotShortUrl" in {
      newUsecase().fromShortUrl("https://other.example/abcd1234").futureValue shouldBe
        Left(ResolveShortLinkError.NotShortUrl)
    }

    "URL でなければ NotShortUrl" in {
      newUsecase().fromShortUrl("abcd1234").futureValue shouldBe
        Left(ResolveShortLinkError.NotShortUrl)
    }
  }
}
