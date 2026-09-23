package domain

import scala.concurrent.Future

/** [[ShortLinkRepository.saveIfAbsent]] の結果。 */
enum SaveResult {
  case Saved

  /** 同じコードが既に使われている。呼び出し側で採番し直す。 */
  case CodeTaken

  /** 同じ URL が既に登録されている。並行リクエストに先を越されたときに起きる。 */
  case UrlExists(existing: ShortLink)
}

/** 短縮リンクの永続化。実装が inmemory か DB かをユースケースに意識させないための抽象。 */
trait ShortLinkRepository {

  /** コードも URL も未登録のときだけ保存する。判定と保存は不可分に行う。 */
  def saveIfAbsent(link: ShortLink): Future[SaveResult]
  def findByCode(code: String): Future[Option[ShortLink]]
  def findByUrl(url: Url): Future[Option[ShortLink]]
}
