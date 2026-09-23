package service

import domain.Url
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DefaultShortLinkServiceSpec extends AnyWordSpec with Matchers {

  "DefaultShortLinkService.generate" should {

    "英大文字・小文字・数字だけの 8 文字のコードを振る" in {
      val service = new DefaultShortLinkService()
      val url = Url.from("https://example.com").toOption.get

      (1 to 1000).foreach { _ =>
        service.generate(url).code should fullyMatch regex "[A-Za-z0-9]{8}"
      }
    }

    "渡した url をそのまま持つ" in {
      val url = Url.from("https://example.com").toOption.get
      new DefaultShortLinkService().generate(url).url shouldBe url
    }
  }
}
