package usecase

import domain.{ServiceHost, ShortLink, ShortLinkRepository, ShortLinkService, Url}
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

enum CreateShortLinkError {
  case InvalidUrl(cause: Url.Error)
  case SelfReference(host: String)
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

  def execute(rawUrl: String): Future[Either[CreateShortLinkError, ShortLink]] =
    Url.from(rawUrl) match {
      case Left(error) =>
        Future.successful(Left(CreateShortLinkError.InvalidUrl(error)))
      // 自サービス宛の短縮を許すとリダイレクトが無限に連鎖しうる。
      case Right(url) if serviceHost.matches(url.host) =>
        Future.successful(Left(CreateShortLinkError.SelfReference(url.host)))
      case Right(url) =>
        val link = shortLinkService.generate(url)
        repository.save(link).map(_ => Right(link))
    }
}
