package domain

import scala.concurrent.Future

/** 検証済みの Url に一意なコードを振り、保存まで行う。
  *
  * 重複の避け方 (乱数なら採番し直し、連番なら不要) は採番方式次第なので、ユースケースでなく実装側に置く。
  */
trait ShortLinkService {

  /** 同じ URL が並行リクエストで先に登録されていたら、新しく振らずにそのリンクを返す。 */
  def issue(url: Url): Future[Either[ShortLinkService.Error, ShortLink]]
}

object ShortLinkService {
  enum Error {

    /** 採番を上限回数やり直しても空きコードが取れなかった。 */
    case CodeExhausted

    /** 保存できる件数の上限に達している。 */
    case StorageFull
  }
}
