package domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Play を起動しない純粋なユニットテスト。 */
class PublicBaseUrlSpec extends AnyWordSpec with Matchers {

  "PublicBaseUrl.from" should {

    "スキームとホストとポートだけの URL を受け入れる" in {
      PublicBaseUrl.from("https://example.com").map(_.value) shouldBe Right("https://example.com")
      PublicBaseUrl.from("http://localhost:5173").map(_.value) shouldBe Right(
        "http://localhost:5173"
      )
    }

    "末尾のスラッシュを落とし、ホストを小文字にそろえる" in {
      PublicBaseUrl.from(" https://Example.COM/ ").map(_.value) shouldBe Right(
        "https://example.com"
      )
    }

    "http/https 以外を拒否する" in {
      PublicBaseUrl.from("ftp://example.com").isLeft shouldBe true
      PublicBaseUrl.from("example.com").isLeft shouldBe true
    }

    "パス・クエリ・認証情報付きを拒否する" in {
      PublicBaseUrl.from("https://example.com/s").isLeft shouldBe true
      PublicBaseUrl.from("https://example.com?a=b").isLeft shouldBe true
      PublicBaseUrl.from("https://user:pass@example.com").isLeft shouldBe true
    }

    "URL として壊れていれば拒否する" in {
      PublicBaseUrl.from("https://exa mple.com").isLeft shouldBe true
    }
  }

  "linkTo" should {
    "コードをパスにした短縮 URL を返す" in {
      PublicBaseUrl.from("https://example.com").map(_.linkTo("Xk3pR8vN")) shouldBe Right(
        "https://example.com/Xk3pR8vN"
      )
    }
  }

  "host" should {
    "ポートを含まないホスト名を返す" in {
      PublicBaseUrl.from("http://localhost:5173").map(_.host) shouldBe Right(
        ServiceHost("localhost")
      )
    }
  }
}
