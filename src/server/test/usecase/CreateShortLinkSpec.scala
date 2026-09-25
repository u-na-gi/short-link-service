package usecase

import domain.{ServiceHost, ShortLink, ShortLinkRepository, ShortLinkService, Url}
import infra.inmemory.InMemoryShortLinkRepository
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{ExecutionContext, Future}
import service.DefaultShortLinkService
import support.SequenceCodes

/** Built with new, without the DI container, so it can be tested without starting the app.
  *
  * Retry behavior is covered in DefaultShortLinkServiceSpec. Here codes are pinned to check only
  * the business flow.
  */
class CreateShortLinkSpec extends AnyWordSpec with Matchers with ScalaFutures {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private def newUsecase(
      repository: ShortLinkRepository = new InMemoryShortLinkRepository(),
      serviceHost: ServiceHost = ServiceHost("short.example"),
      codes: () => String = new SequenceCodes("fixed123")
  ) = new CreateShortLink(repository, new DefaultShortLinkService(repository, codes), serviceHost)

  "CreateShortLink.execute" should {

    "return a short link for a valid URL" in {
      val result = newUsecase().execute("https://example.com").futureValue
      result.map(_.code) shouldBe Right("fixed123")
      result.map(_.url.value) shouldBe Right("https://example.com/")
    }

    "save the issued link" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("https://example.com").futureValue

      val saved = repository.findByCode("fixed123").futureValue
      saved.map(_.url.value) shouldBe Some("https://example.com/")
    }

    "return InvalidUrl for an empty string" in {
      newUsecase().execute("").futureValue shouldBe
        Left(CreateShortLinkError.InvalidUrl(Url.Error.Empty))
    }

    "return InvalidUrl for ftp" in {
      newUsecase().execute("ftp://example.com").futureValue shouldBe
        Left(CreateShortLinkError.InvalidUrl(Url.Error.UnsupportedScheme("ftp")))
    }

    "not save an invalid URL" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("not a url").futureValue.isLeft shouldBe true
      repository.findByCode("fixed123").futureValue shouldBe None
    }

    // Executable schemes like javascript: are rejected by the allow list.
    "reject javascript: with InvalidUrl" in {
      newUsecase().execute("javascript:alert(1)").futureValue shouldBe
        Left(CreateShortLinkError.InvalidUrl(Url.Error.UnsupportedScheme("javascript")))
    }

    "accept a normal https URL even with an executable-looking extension" in {
      newUsecase().execute("https://example.com/install.sh").futureValue.isRight shouldBe true
    }

    "reject a url to this service with SelfReference" in {
      newUsecase().execute("https://short.example/abcd1234").futureValue shouldBe
        Left(CreateShortLinkError.SelfReference("short.example"))
    }

    "ignore case when checking for this service" in {
      newUsecase().execute("https://SHORT.example/x").futureValue.isLeft shouldBe true
    }

    "not save a url to this service" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("https://short.example/x").futureValue.isLeft shouldBe true
      repository.findByCode("fixed123").futureValue shouldBe None
    }

    "allow other hosts" in {
      newUsecase().execute("https://other.example/x").futureValue.isRight shouldBe true
    }

    "return the same link for the same URL without generating a code the second time" in {
      val codes = new SequenceCodes("first001", "second02")
      val usecase = newUsecase(codes = codes)

      val first = usecase.execute("https://example.com").futureValue
      // Same link if it is the same URL after normalization.
      val second = usecase.execute("https://EXAMPLE.com/").futureValue

      first.map(_.code) shouldBe Right("first001")
      second.map(_.code) shouldBe Right("first001")
      codes.calls shouldBe 1
    }

    "assign different codes to different URLs" in {
      val usecase = newUsecase(codes = new SequenceCodes("first001", "second02"))
      usecase.execute("https://a.example").futureValue.map(_.code) shouldBe Right("first001")
      usecase.execute("https://b.example").futureValue.map(_.code) shouldBe Right("second02")
    }

    "return CodeExhausted when no code can be generated" in {
      val exhausted = new ShortLinkService {
        override def issue(url: Url): Future[Either[ShortLinkService.Error, ShortLink]] =
          Future.successful(Left(ShortLinkService.Error.CodeExhausted))
      }
      new CreateShortLink(
        new InMemoryShortLinkRepository(),
        exhausted,
        ServiceHost("short.example")
      )
        .execute("https://example.com")
        .futureValue shouldBe Left(CreateShortLinkError.CodeExhausted)
    }

    "return StorageFull when the count limit is reached" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 1)
      val usecase = newUsecase(repository, codes = new SequenceCodes("first001", "second02"))
      usecase.execute("https://a.example").futureValue

      usecase.execute("https://b.example").futureValue shouldBe
        Left(CreateShortLinkError.StorageFull)
    }

    "return the existing link for a registered URL even at the count limit" in {
      val repository = new InMemoryShortLinkRepository(maxLinks = 1)
      val usecase = newUsecase(repository, codes = new SequenceCodes("first001", "second02"))
      usecase.execute("https://a.example").futureValue

      usecase.execute("https://a.example").futureValue.map(_.code) shouldBe Right("first001")
    }
  }
}
