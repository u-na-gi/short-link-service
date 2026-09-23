package usecase

import domain.{ServiceHost, ShortLink, ShortLinkRepository, ShortLinkService, Url}
import infra.inmemory.InMemoryShortLinkRepository
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.ExecutionContext

/** DI コンテナを使わず new で組み立てられるので、アプリを起動せずに検証できる。 */
class CreateShortLinkSpec extends AnyWordSpec with Matchers with ScalaFutures {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  /** コードの採番をテストから固定するためのスタブ。 */
  private val fixedCodeService = new ShortLinkService {
    override def generate(url: Url): ShortLink = ShortLink("fixed123", url)
  }

  private def newUsecase(
      repository: ShortLinkRepository = new InMemoryShortLinkRepository(),
      serviceHost: ServiceHost = ServiceHost("short.example")
  ) = new CreateShortLink(repository, fixedCodeService, serviceHost)

  "CreateShortLink.execute" should {

    "正しいURLで短縮リンクを返す" in {
      val result = newUsecase().execute("https://example.com").futureValue
      result.map(_.code) shouldBe Right("fixed123")
      result.map(_.url.value) shouldBe Right("https://example.com/")
    }

    "発行したリンクを保存する" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("https://example.com").futureValue

      val saved = repository.findByCode("fixed123").futureValue
      saved.map(_.url.value) shouldBe Some("https://example.com/")
    }

    "空文字で InvalidUrl を返す" in {
      newUsecase().execute("").futureValue shouldBe
        Left(CreateShortLinkError.InvalidUrl(Url.Error.Empty))
    }

    "ftp で InvalidUrl を返す" in {
      newUsecase().execute("ftp://example.com").futureValue shouldBe
        Left(CreateShortLinkError.InvalidUrl(Url.Error.UnsupportedScheme("ftp")))
    }

    "不正なURLのときは保存しない" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("not a url").futureValue.isLeft shouldBe true
      repository.findByCode("fixed123").futureValue shouldBe None
    }

    // javascript: などの実行系スキームはホワイトリストで落ちる。
    "javascript: を InvalidUrl で拒否する" in {
      newUsecase().execute("javascript:alert(1)").futureValue shouldBe
        Left(CreateShortLinkError.InvalidUrl(Url.Error.UnsupportedScheme("javascript")))
    }

    "実行ファイルらしき拡張子でも通常の https なら受け入れる" in {
      newUsecase().execute("https://example.com/install.sh").futureValue.isRight shouldBe true
    }

    "自サービス宛の url を SelfReference で拒否する" in {
      newUsecase().execute("https://short.example/abcd1234").futureValue shouldBe
        Left(CreateShortLinkError.SelfReference("short.example"))
    }

    "自サービス宛の判定は大文字小文字を無視する" in {
      newUsecase().execute("https://SHORT.example/x").futureValue.isLeft shouldBe true
    }

    "自サービス宛の url は保存しない" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("https://short.example/x").futureValue.isLeft shouldBe true
      repository.findByCode("fixed123").futureValue shouldBe None
    }

    "他ホストなら通す" in {
      newUsecase().execute("https://other.example/x").futureValue.isRight shouldBe true
    }
  }
}
