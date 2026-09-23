package domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Play を起動しない純粋なユニットテスト。 */
class UrlSpec extends AnyWordSpec with Matchers {

  "Url.from" should {

    "http/https の絶対URLを受け入れる" in {
      Url.from("https://example.com/").map(_.value) shouldBe Right("https://example.com/")
      Url.from("http://example.com/a?b=c").map(_.value) shouldBe Right("http://example.com/a?b=c")
    }

    "前後の空白を取り除く" in {
      Url.from("  https://example.com/  ").map(_.value) shouldBe Right("https://example.com/")
    }

    "空文字を拒否する" in {
      Url.from("") shouldBe Left(Url.Error.Empty)
      Url.from("   ") shouldBe Left(Url.Error.Empty)
    }

    "http/https 以外のスキームを拒否する" in {
      Url.from("ftp://example.com") shouldBe Left(Url.Error.UnsupportedScheme("ftp"))
      Url.from("javascript:alert(1)") shouldBe Left(Url.Error.UnsupportedScheme("javascript"))
    }

    "スキームの無い文字列を拒否する" in {
      Url.from("example.com").isLeft shouldBe true
      Url.from("not a url").isLeft shouldBe true
    }

    "ホストの無いURLを拒否する" in {
      Url.from("http://").isLeft shouldBe true
    }

    "認証情報つきの url を拒否する" in {
      Url.from("https://user:pass@evil.example.com/") shouldBe Left(Url.Error.ContainsCredentials)
      Url.from("https://user@evil.example.com/") shouldBe Left(Url.Error.ContainsCredentials)
    }

    "日本語ドメインを punycode に正規化して受け入れる" in {
      Url.from("https://例え.テスト/").map(_.value) shouldBe
        Right("https://xn--r8jz45g.xn--zckzah/")
    }

    "punycode で渡された場合はそのまま受け入れる" in {
      Url.from("https://xn--r8jz45g.xn--zckzah/").map(_.value) shouldBe
        Right("https://xn--r8jz45g.xn--zckzah/")
    }

    "ポートやパスを保ったまま正規化する" in {
      Url.from("https://例え.テスト:8443/a?b=c#d").map(_.value) shouldBe
        Right("https://xn--r8jz45g.xn--zckzah:8443/a?b=c#d")
    }

    "パスが空なら / を補い、ホストを小文字に正規化する" in {
      Url.from("https://EXAMPLE.com").map(_.value) shouldBe Right("https://example.com/")
    }

    "スキームを小文字に正規化する" in {
      Url.from("HTTPS://example.com").map(_.value) shouldBe Right("https://example.com/")
      Url.from("HtTp://example.com/a").map(_.value) shouldBe Right("http://example.com/a")
    }

    "host を小文字で取り出せる" in {
      Url.from("https://EXAMPLE.com/x").map(_.host) shouldBe Right("example.com")
    }

    "長すぎるURLを拒否する" in {
      val tooLong = "https://example.com/" + "a" * 2048
      Url.from(tooLong) shouldBe Left(Url.Error.TooLong(tooLong.length))
    }

    "ちょうど上限の 2048 文字は受け入れ、2049 文字は拒否する" in {
      val prefix = "https://example.com/"
      val atLimit = prefix + "a" * (2048 - prefix.length)
      val overLimit = atLimit + "a"
      Url.from(atLimit).map(_.value) shouldBe Right(atLimit)
      Url.from(overLimit) shouldBe Left(Url.Error.TooLong(2049))
    }

    "正規化で上限を超えた場合も拒否する" in {
      // 入力は 720 文字だが、"あ" は "%E3%81%82" (9 文字) にエンコードされて 6320 文字になる。
      val raw = "https://example.com/" + "あ" * 700
      raw.length should be <= 2048
      Url.from(raw) shouldBe Left(Url.Error.TooLong(6320))
    }
  }
}
