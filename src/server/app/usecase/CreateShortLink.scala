package usecase

import domain.{ServiceHost, ShortLink, ShortLinkRepository, ShortLinkService, Url}
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

enum CreateShortLinkError {
  case InvalidUrl(cause: Url.Error)
  case SelfReference(host: String)

  /** 採番を上限回数やり直しても空きコードが取れなかった。 */
  case CodeExhausted

  /** 保存できる件数の上限に達している。登録済みの URL なら上限に関係なく既存のリンクを返す。 */
  case StorageFull
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
        // 登録済みなら乱数を引かずにそのまま返す。
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
