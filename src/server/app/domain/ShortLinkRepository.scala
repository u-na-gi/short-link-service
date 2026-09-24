package domain

import scala.concurrent.Future

/** [[ShortLinkRepository.saveIfAbsent]] の結果。 */
enum SaveResult {
  case Saved

  /** 同じコードが既に使われている。呼び出し側で採番し直す。 */
  case CodeTaken

  /** 同じ URL が既に登録されている。並行リクエストに先を越されたときに起きる。 */
  case UrlExists(existing: ShortLink)

  /** 保存できる件数の上限に達している。 */
  case Full
}

/** 短縮リンクの永続化。 */
trait ShortLinkRepository {

  /** コードも URL も未登録で、件数に空きがあるときだけ保存する。判定と保存は不可分に行う。
    *
    * URL が登録済みなら、上限に達していても既存のリンクを返す (新しく保存しないので件数は増えない)。
    */
  def saveIfAbsent(link: ShortLink): Future[SaveResult]
  def findByCode(code: String): Future[Option[ShortLink]]
  def findByUrl(url: Url): Future[Option[ShortLink]]
}
