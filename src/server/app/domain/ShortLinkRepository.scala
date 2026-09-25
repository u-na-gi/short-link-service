package domain

import scala.concurrent.Future

/** Result of [[ShortLinkRepository.saveIfAbsent]]. */
enum SaveResult {
  case Saved

  /** The same code is already used. The caller generates a new code. */
  case CodeTaken

  /** The same URL is already registered. Happens when a concurrent request got there first. */
  case UrlExists(existing: ShortLink)

  /** The limit on the number of stored links has been reached. */
  case Full
}

/** Storage for short links. */
trait ShortLinkRepository {

  /** Saves only when neither the code nor the URL is registered and there is room. The check and
    * the save are atomic.
    *
    * If the URL is registered, returns the existing link even at the limit (nothing new is saved,
    * so the count does not grow).
    */
  def saveIfAbsent(link: ShortLink): Future[SaveResult]
  def findByCode(code: String): Future[Option[ShortLink]]
  def findByUrl(url: Url): Future[Option[ShortLink]]
}
