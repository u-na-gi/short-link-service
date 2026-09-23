package usecase

import domain.{ShortLink, ShortLinkRepository}
import javax.inject._
import scala.concurrent.Future

/** 短縮URLのコードから発行済みのリンクを引く。 */
@Singleton
class ResolveShortLink @Inject() (repository: ShortLinkRepository) {

  def execute(code: String): Future[Option[ShortLink]] = repository.findByCode(code)
}
