package infra.inmemory

import domain.{SaveResult, ShortLink, ShortLinkRepository, Url}
import javax.inject._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/** Max number of stored links. `Module` creates it from the `shortener.max-links` setting. */
final case class LinkCapacity(maxLinks: Int)

/** Implementation that stores links in in-process Maps. Lost on restart.
  *
  * The write API is public, so the count is limited to keep it from using up memory. `maxLinks`
  * defaults to no limit (for tests). Production uses the configured value through the auxiliary
  * constructor for DI.
  */
@Singleton
class InMemoryShortLinkRepository(maxLinks: Int = Int.MaxValue) extends ShortLinkRepository {

  @Inject()
  def this(capacity: LinkCapacity) = this(capacity.maxLinks)

  private val byCode = TrieMap.empty[String, ShortLink]

  /** Reverse lookup to return the same code for the same URL. The key is the normalized
    * `Url.value`.
    */
  private val byUrl = TrieMap.empty[String, ShortLink]

  /** Number of saved links. `TrieMap.size` walks everything (O(n)), so count inside the same lock
    * as writes.
    */
  private var count = 0

  /** Checks and updates span two Maps, so only writes are serialized. Reads do not lock. */
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
