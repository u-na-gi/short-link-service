package domain

/** 検証済みの Url から短縮リンクを発行する。入力が VO で保証済みなので失敗しない。 */
trait ShortLinkService {
  def generate(url: Url): ShortLink
}
