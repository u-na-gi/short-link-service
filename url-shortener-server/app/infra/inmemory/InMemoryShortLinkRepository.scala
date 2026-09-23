package infra.inmemory

import domain.{ShortLink, ShortLinkRepository}
import javax.inject._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/** プロセス内 Map に保存する実装。再起動で消えるため開発・テスト用。 */
@Singleton
class InMemoryShortLinkRepository @Inject() () extends ShortLinkRepository {

  private val store = TrieMap.empty[String, ShortLink]

  override def save(link: ShortLink): Future[Unit] = {
    store.put(link.code, link)
    Future.successful(())
  }

  override def findByCode(code: String): Future[Option[ShortLink]] =
    Future.successful(store.get(code))
}
