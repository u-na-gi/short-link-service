package domain

/** An issued short link. `code` is the path part of the short URL. */
final case class ShortLink(code: String, url: Url)
