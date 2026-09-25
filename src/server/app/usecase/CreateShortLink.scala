package usecase

import domain.{ServiceHost, ShortLink, ShortLinkRepository, ShortLinkService, Url}
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

enum CreateShortLinkError {
  case InvalidUrl(cause: Url.Error)
  case SelfReference(host: String)

  /** No free code was found even after retrying the maximum number of times. */
  case CodeExhausted

  /** The limit on the number of stored links has been reached. A registered URL gets its existing
    * link regardless of the limit.
    */
  case StorageFull
}

/** Business operation: "take a URL, issue a short link, and save it".
  *
  * It takes the raw string and converts it to a value object inside, so the controller only handles
  * HTTP concerns.
  */
@Singleton
class CreateShortLink @Inject() (
    repository: ShortLinkRepository,
    shortLinkService: ShortLinkService,
    serviceHost: ServiceHost
)(implicit ec: ExecutionContext) {

  def execute(rawUrl: String): Future[Either[CreateShortLinkError, ShortLink]] =
    Url.from(rawUrl) match {
      case Left(error) =>
        Future.successful(Left(CreateShortLinkError.InvalidUrl(error)))
      // Allowing short links to this service itself could chain redirects forever.
      case Right(url) if serviceHost.matches(url.host) =>
        Future.successful(Left(CreateShortLinkError.SelfReference(url.host)))
      case Right(url) =>
        // If already registered, return it as-is without generating a random code.
        repository.findByUrl(url).flatMap {
          case Some(existing) => Future.successful(Right(existing))
          case None           =>
            shortLinkService
              .issue(url)
              .map(_.left.map {
                case ShortLinkService.Error.CodeExhausted => CreateShortLinkError.CodeExhausted
                case ShortLinkService.Error.StorageFull   => CreateShortLinkError.StorageFull
              })
        }
    }
}
