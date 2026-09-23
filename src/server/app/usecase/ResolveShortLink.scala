package usecase

import domain.{PublicBaseUrl, ShortLink, ShortLinkRepository}
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

enum ResolveShortLinkError {

  /** 自サービスの短縮 URL の形ではない。 */
  case NotShortUrl

  /** 形は正しいが発行されていない。インメモリなので、再起動で消えた場合もここになる。 */
  case NotFound
}

/** 短縮URLから発行済みのリンクを引く。 */
@Singleton
class ResolveShortLink @Inject() (repository: ShortLinkRepository, baseUrl: PublicBaseUrl)(implicit
    ec: ExecutionContext
) {

  /** コードで引く。短縮URLへのアクセス (`GET /:code`) からリダイレクトするときに使う。 */
  def execute(code: String): Future[Option[ShortLink]] = repository.findByCode(code)

  /** 利用者が貼り付けた短縮URLの文字列から引く。 */
  def fromShortUrl(rawShortUrl: String): Future[Either[ResolveShortLinkError, ShortLink]] =
    baseUrl.codeOf(rawShortUrl) match {
      case None       => Future.successful(Left(ResolveShortLinkError.NotShortUrl))
      case Some(code) => execute(code).map(_.toRight(ResolveShortLinkError.NotFound))
    }
}
