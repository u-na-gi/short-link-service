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

  /** 渡したコードを順に返し、呼ばれた回数を数えるスタブ。使い切ったら最後のコードを返し続ける。 */
  private class SequenceCodeService(codes: String*) extends ShortLinkService {
    var calls = 0
    override def generate(url: Url): ShortLink = {
      val code = codes(math.min(calls, codes.length - 1))
      calls += 1
      ShortLink(code, url)
    }
  }

  private def newUsecase(
      repository: ShortLinkRepository = new InMemoryShortLinkRepository(),
      serviceHost: ServiceHost = ServiceHost("short.example"),
      shortLinkService: ShortLinkService = fixedCodeService
  ) = new CreateShortLink(repository, shortLinkService, serviceHost)

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

    "同じURLには同じリンクを返し、2 回目は採番しない" in {
      val service = new SequenceCodeService("first001", "second02")
      val usecase = newUsecase(shortLinkService = service)

      val first = usecase.execute("https://example.com").futureValue
      // 正規化後に同じ URL になるなら同じリンク。
      val second = usecase.execute("https://EXAMPLE.com/").futureValue

      first.map(_.code) shouldBe Right("first001")
      second.map(_.code) shouldBe Right("first001")
      service.calls shouldBe 1
    }

    "違うURLには別のコードを振る" in {
      val usecase = newUsecase(shortLinkService = new SequenceCodeService("first001", "second02"))
      usecase.execute("https://a.example").futureValue.map(_.code) shouldBe Right("first001")
      usecase.execute("https://b.example").futureValue.map(_.code) shouldBe Right("second02")
    }

    "コードが被ったら採番し直す" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("https://taken.example").futureValue

      val service = new SequenceCodeService("fixed123", "fixed123", "fresh001")
      val result = newUsecase(repository, shortLinkService = service)
        .execute("https://example.com")
        .futureValue

      result.map(_.code) shouldBe Right("fresh001")
      service.calls shouldBe 3
      repository.findByCode("fresh001").futureValue.map(_.url.value) shouldBe
        Some("https://example.com/")
      // 被った側のリンクは上書きされない。
      repository.findByCode("fixed123").futureValue.map(_.url.value) shouldBe
        Some("https://taken.example/")
    }

    "被り続けたら上限回数で CodeExhausted を返す" in {
      val repository = new InMemoryShortLinkRepository()
      newUsecase(repository).execute("https://taken.example").futureValue

      val service = new SequenceCodeService("fixed123")
      newUsecase(repository, shortLinkService = service)
        .execute("https://example.com")
        .futureValue shouldBe Left(CreateShortLinkError.CodeExhausted)
      service.calls shouldBe 10
    }
  }
}
