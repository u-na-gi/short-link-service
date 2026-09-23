package domain

/** 発行済みの短縮リンク。`code` は短縮URLのパス部分にあたる。 */
final case class ShortLink(code: String, url: Url)
