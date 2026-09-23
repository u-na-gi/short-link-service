package infra.inmemory

import domain.{SaveResult, ShortLink, ShortLinkRepository, Url}
import javax.inject._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/** プロセス内 Map に保存する実装。再起動で消えるため開発・テスト用。 */
@Singleton
class InMemoryShortLinkRepository @Inject() () extends ShortLinkRepository {

  private val byCode = TrieMap.empty[String, ShortLink]

  /** 同じ URL に同じコードを返すための逆引き。キーは正規化済みの `Url.value`。 */
  private val byUrl = TrieMap.empty[String, ShortLink]

  /** 2 つの Map をまたいで判定・更新するので書き込みだけ直列化する。読み取りはロックしない。 */
  override def saveIfAbsent(link: ShortLink): Future[SaveResult] = {
    val result = synchronized {
      byUrl.get(link.url.value) match {
        case Some(existing)                     => SaveResult.UrlExists(existing)
        case None if byCode.contains(link.code) => SaveResult.CodeTaken
        case None                               =>
          byCode.put(link.code, link)
          byUrl.put(link.url.value, link)
          SaveResult.Saved
      }
    }
    Future.successful(result)
  }

  override def findByCode(code: String): Future[Option[ShortLink]] =
    Future.successful(byCode.get(code))

  override def findByUrl(url: Url): Future[Option[ShortLink]] =
    Future.successful(byUrl.get(url.value))
}
