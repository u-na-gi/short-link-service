package service

import domain.{ShortLink, ShortLinkService, Url}
import java.security.SecureRandom
import javax.inject._

/** ランダムなコードを振るだけの素朴な実装。衝突制御が必要になったらここを差し替える。 */
@Singleton
class DefaultShortLinkService @Inject() () extends ShortLinkService {
  import DefaultShortLinkService._

  private val random = new SecureRandom()

  override def generate(url: Url): ShortLink = ShortLink(randomCode(), url)

  private def randomCode(): String =
    (1 to CodeLength).map(_ => Alphabet(random.nextInt(Alphabet.length))).mkString
}

object DefaultShortLinkService {
  private val Alphabet: IndexedSeq[Char] = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
  private val CodeLength = 8
}
