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

  /** Builds it with a repository where link is already registered. */
  private def newUsecase() = {
    val repository = new InMemoryShortLinkRepository()
    repository.saveIfAbsent(link).futureValue
    new ResolveShortLink(repository, baseUrl)
  }

  "ResolveShortLink.execute" should {

    "return the link for an issued code" in {
      newUsecase().execute("abcd1234").futureValue shouldBe Some(link)
    }

    "return None for an unknown code" in {
      newUsecase().execute("zzzzzzzz").futureValue shouldBe None
    }
  }

  "ResolveShortLink.fromShortUrl" should {

    "return the link for an issued short URL" in {
      newUsecase().fromShortUrl("https://example.com/abcd1234").futureValue shouldBe Right(link)
    }

    "return NotFound when the form is correct but it was never issued" in {
      newUsecase().fromShortUrl("https://example.com/zzzzzzzz").futureValue shouldBe
        Left(ResolveShortLinkError.NotFound)
    }

    "return NotShortUrl for a URL on another host" in {
      newUsecase().fromShortUrl("https://other.example/abcd1234").futureValue shouldBe
        Left(ResolveShortLinkError.NotShortUrl)
    }

    "return NotShortUrl for something that is not a URL" in {
      newUsecase().fromShortUrl("abcd1234").futureValue shouldBe
        Left(ResolveShortLinkError.NotShortUrl)
    }
  }
}
