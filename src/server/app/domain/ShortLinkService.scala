package domain

import scala.concurrent.Future

/** Assigns a unique code to a validated Url and saves it.
  *
  * How to avoid duplicates (retry for random codes, nothing for sequential ones) depends on how
  * codes are generated, so it belongs in the implementation, not the usecase.
  */
trait ShortLinkService {

  /** If a concurrent request already registered the same URL, returns that link instead of a new
    * code.
    */
  def issue(url: Url): Future[Either[ShortLinkService.Error, ShortLink]]
}

object ShortLinkService {
  enum Error {

    /** No free code was found even after retrying the maximum number of times. */
    case CodeExhausted

    /** The limit on the number of stored links has been reached. */
    case StorageFull
  }
}
