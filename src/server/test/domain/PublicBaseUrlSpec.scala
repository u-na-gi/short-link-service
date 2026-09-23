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
      PublicBaseUrl.from("ftp://example.com") shouldBe Left(
        PublicBaseUrl.Error.UnsupportedScheme(Some("ftp"))
      )
      PublicBaseUrl.from("example.com") shouldBe Left(PublicBaseUrl.Error.UnsupportedScheme(None))
    }

    "ホスト名が無ければ拒否する" in {
      PublicBaseUrl.from("https:///") shouldBe Left(PublicBaseUrl.Error.MissingHost)
    }

    "パス・クエリ・フラグメント・認証情報付きを拒否する" in {
      PublicBaseUrl.from("https://example.com/s") shouldBe Left(PublicBaseUrl.Error.HasPath("/s"))
      PublicBaseUrl.from("https://example.com?a=b") shouldBe Left(PublicBaseUrl.Error.HasQuery)
      PublicBaseUrl.from("https://example.com#top") shouldBe Left(PublicBaseUrl.Error.HasFragment)
      PublicBaseUrl.from("https://user:pass@example.com") shouldBe Left(
        PublicBaseUrl.Error.HasUserInfo
      )
    }

    "URL として壊れていればパースの例外ごと返す" in {
      PublicBaseUrl.from("https://exa mple.com") should matchPattern {
        case Left(PublicBaseUrl.Error.Malformed(_)) =>
      }
    }
  }

  "linkTo" should {
    "コードをパスにした短縮 URL を返す" in {
      PublicBaseUrl.from("https://example.com").map(_.linkTo("Xk3pR8vN")) shouldBe Right(
        "https://example.com/Xk3pR8vN"
      )
    }
  }

  "codeOf" should {
    val base = PublicBaseUrl.from("https://example.com").toOption.get

    "自サービスの短縮 URL からコードを取り出す" in {
      base.codeOf("https://example.com/Xk3pR8vN") shouldBe Some("Xk3pR8vN")
    }

    "前後の空白・ホストの大文字・スキームやポートの違いは問わない" in {
      base.codeOf(" https://EXAMPLE.com/Xk3pR8vN ") shouldBe Some("Xk3pR8vN")
      base.codeOf("http://example.com:8080/Xk3pR8vN") shouldBe Some("Xk3pR8vN")
    }

    "クエリとフラグメントは無視する" in {
      base.codeOf("https://example.com/Xk3pR8vN?utm_source=x#top") shouldBe Some("Xk3pR8vN")
    }

    "コードの大文字小文字は区別したまま返す" in {
      base.codeOf("https://example.com/XK3PR8VN") shouldBe Some("XK3PR8VN")
    }

    "他ホストなら None" in {
      base.codeOf("https://other.example/Xk3pR8vN") shouldBe None
      base.codeOf("https://sub.example.com/Xk3pR8vN") shouldBe None
    }

    "パスが英数 8 文字の 1 階層でなければ None" in {
      base.codeOf("https://example.com/") shouldBe None
      base.codeOf("https://example.com/qEmT9gT") shouldBe None
      base.codeOf("https://example.com/Xk3pR8vNX") shouldBe None
      base.codeOf("https://example.com/qEmT9gT-") shouldBe None
      base.codeOf("https://example.com/api/v1/links") shouldBe None
      base.codeOf("https://example.com/Xk3pR8vN/") shouldBe None
    }

    "URL として解釈できなければ None" in {
      base.codeOf("") shouldBe None
      base.codeOf("example.com/Xk3pR8vN") shouldBe None
      base.codeOf("Xk3pR8vN") shouldBe None
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
