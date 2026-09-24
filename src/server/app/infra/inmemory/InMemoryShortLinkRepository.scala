package infra.inmemory

import domain.{SaveResult, ShortLink, ShortLinkRepository, Url}
import javax.inject._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/** 保存できる件数の上限。設定 `shortener.max-links` から `Module` が作る。 */
final case class LinkCapacity(maxLinks: Int)

/** プロセス内 Map に保存する実装。再起動で消える。
  *
  * 公開の書き込み API なので、件数に上限を設けてメモリを使い切られないようにする。 `maxLinks` の既定値は上限なし (テスト用)。本番は DI
  * 用の補助コンストラクタで設定の値を使う。
  */
@Singleton
class InMemoryShortLinkRepository(maxLinks: Int = Int.MaxValue) extends ShortLinkRepository {

  @Inject()
  def this(capacity: LinkCapacity) = this(capacity.maxLinks)

  private val byCode = TrieMap.empty[String, ShortLink]

  /** 同じ URL に同じコードを返すための逆引き。キーは正規化済みの `Url.value`。 */
  private val byUrl = TrieMap.empty[String, ShortLink]

  /** 保存した件数。`TrieMap.size` は全体をたどる (O(n)) ので、書き込みと同じロックの中で数える。 */
  private var count = 0

  /** 2 つの Map をまたいで判定・更新するので書き込みだけ直列化する。読み取りはロックしない。 */
  override def saveIfAbsent(link: ShortLink): Future[SaveResult] = {
    val result = synchronized {
      byUrl.get(link.url.value) match {
        case Some(existing)                     => SaveResult.UrlExists(existing)
        case None if count >= maxLinks          => SaveResult.Full
        case None if byCode.contains(link.code) => SaveResult.CodeTaken
        case None                               =>
          byCode.put(link.code, link)
          byUrl.put(link.url.value, link)
          count += 1
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
