package usecase

import domain.{PublicBaseUrl, ShortLink, ShortLinkRepository}
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

enum ResolveShortLinkError {

  /** Not in the form of a short URL of this service. */
  case NotShortUrl

  /** The form is correct but it was never issued. Storage is in memory, so links lost on restart
    * also end up here.
    */
  case NotFound
}

/** Looks up an issued link from a short URL. */
@Singleton
class ResolveShortLink @Inject() (repository: ShortLinkRepository, baseUrl: PublicBaseUrl)(implicit
    ec: ExecutionContext
) {

  /** Looks up by code. Used to redirect on access to a short URL (`GET /:code`). */
  def execute(code: String): Future[Option[ShortLink]] = repository.findByCode(code)

  /** Looks up from a short URL string pasted by the user. */
  def fromShortUrl(rawShortUrl: String): Future[Either[ResolveShortLinkError, ShortLink]] =
    baseUrl.codeOf(rawShortUrl) match {
      case None       => Future.successful(Left(ResolveShortLinkError.NotShortUrl))
      case Some(code) => execute(code).map(_.toRight(ResolveShortLinkError.NotFound))
    }
}
