package service

import domain.{SaveResult, ShortLink, ShortLinkRepository, ShortLinkService, Url}
import java.security.SecureRandom
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/** Implementation that assigns random codes and generates a new one on collision.
  *
  * `nextCode` is a hook for tests to pin codes. Production uses random codes through the auxiliary
  * constructor for DI.
  */
@Singleton
class DefaultShortLinkService(
    repository: ShortLinkRepository,
    nextCode: () => String
)(implicit ec: ExecutionContext)
    extends ShortLinkService {
  import DefaultShortLinkService._

  @Inject()
  def this(repository: ShortLinkRepository)(implicit ec: ExecutionContext) =
    this(repository, () => DefaultShortLinkService.randomCode())

  override def issue(url: Url): Future[Either[ShortLinkService.Error, ShortLink]] =
    issue(url, MaxAttempts)

  private def issue(
      url: Url,
      attemptsLeft: Int
  ): Future[Either[ShortLinkService.Error, ShortLink]] =
    if (attemptsLeft <= 0) Future.successful(Left(ShortLinkService.Error.CodeExhausted))
    else {
      val link = ShortLink(nextCode(), url)
      repository.saveIfAbsent(link).flatMap {
        case SaveResult.Saved               => Future.successful(Right(link))
        case SaveResult.UrlExists(existing) => Future.successful(Right(existing))
        case SaveResult.CodeTaken           => issue(url, attemptsLeft - 1)
        case SaveResult.Full => Future.successful(Left(ShortLinkService.Error.StorageFull))
      }
    }
}

object DefaultShortLinkService {
  private val Alphabet: IndexedSeq[Char] = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
  private val CodeLength = 8

  /** There are 62^8 codes, so repeated collisions do not happen in practice. A limit so a code
    * generation bug does not loop forever.
    */
  private[service] val MaxAttempts = 10

  // SecureRandom is thread-safe, so share one instance.
  private val random = new SecureRandom()

  private[service] def randomCode(): String =
    (1 to CodeLength).map(_ => Alphabet(random.nextInt(Alphabet.length))).mkString
}
