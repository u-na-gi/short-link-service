package domain

import scala.concurrent.Future

/** 短縮リンクの永続化。実装が inmemory か DB かをユースケースに意識させないための抽象。 */
trait ShortLinkRepository {
  def save(link: ShortLink): Future[Unit]
  def findByCode(code: String): Future[Option[ShortLink]]
}
