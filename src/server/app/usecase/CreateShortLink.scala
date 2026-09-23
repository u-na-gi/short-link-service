package usecase

import domain.{SaveResult, ServiceHost, ShortLink, ShortLinkRepository, ShortLinkService, Url}
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

enum CreateShortLinkError {
  case InvalidUrl(cause: Url.Error)
  case SelfReference(host: String)

  /** 採番を上限回数やり直しても空きコードが取れなかった。 */
  case CodeExhausted
}

/** 「URL を受け取って短縮リンクを発行し、保存する」という業務操作。
  *
  * 生の文字列を受け取り VO への変換も内側で行うので、コントローラは HTTP の関心事だけを持てばよい。
  */
@Singleton
class CreateShortLink @Inject() (
    repository: ShortLinkRepository,
    shortLinkService: ShortLinkService,
    serviceHost: ServiceHost
)(implicit ec: ExecutionContext) {
  import CreateShortLink._

  def execute(rawUrl: String): Future[Either[CreateShortLinkError, ShortLink]] =
    Url.from(rawUrl) match {
      case Left(error) =>
        Future.successful(Left(CreateShortLinkError.InvalidUrl(error)))
      // 自サービス宛の短縮を許すとリダイレクトが無限に連鎖しうる。
      case Right(url) if serviceHost.matches(url.host) =>
        Future.successful(Left(CreateShortLinkError.SelfReference(url.host)))
      case Right(url) =>
        // 登録済みなら乱数を引かずにそのまま返す。
        repository.findByUrl(url).flatMap {
          case Some(existing) => Future.successful(Right(existing))
          case None           => issue(url, MaxAttempts)
        }
    }

  private def issue(
      url: Url,
      attemptsLeft: Int
  ): Future[Either[CreateShortLinkError, ShortLink]] =
    if (attemptsLeft <= 0) Future.successful(Left(CreateShortLinkError.CodeExhausted))
    else {
      val link = shortLinkService.generate(url)
      repository.saveIfAbsent(link).flatMap {
        case SaveResult.Saved               => Future.successful(Right(link))
        case SaveResult.UrlExists(existing) => Future.successful(Right(existing))
        case SaveResult.CodeTaken           => issue(url, attemptsLeft - 1)
      }
    }
}

object CreateShortLink {

  /** 62^8 通りあるので実際に何度も被ることはない。採番の不具合で無限ループしないための上限。 */
  private val MaxAttempts = 10
}
