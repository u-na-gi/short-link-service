package domain

import okhttp3.HttpUrl

import java.util.Locale

/** Value object for a validated URL.
  *
  * The constructor is private, so instances are always created through [[Url.from]]. This way the
  * type guarantees that no Url instance holds an invalid value.
  */
final case class Url private (value: String) {

  /** Already validated, so parsing always succeeds and the host is already normalized. */
  val host: String = HttpUrl.get(value).host

  /** The path, still percent-encoded. Does not include the query or fragment. */
  val path: String = HttpUrl.get(value).encodedPath
}

object Url {

  /** The front end holds user-facing messages, so only the kind is returned here. */
  enum Error {
    case Empty
    case Malformed(raw: String)
    case UnsupportedScheme(scheme: String)
    case ContainsCredentials
    case TooLong(length: Int)
  }

  /** Allowed schemes are an allow list, to reject javascript: and data:. */
  private val AllowedSchemes = Set("http", "https")

  /** Matches the practical limit of major browsers. */
  private val MaxLength = 2048

  /** HttpUrl just returns null for anything other than http/https, so extract the scheme first to
    * tell errors apart.
    */
  private val Scheme = """^([A-Za-z][A-Za-z0-9+.-]*):.*""".r

  def from(raw: String): Either[Error, Url] = {
    val trimmed = raw.trim
    if (trimmed.isEmpty) Left(Error.Empty)
    else if (trimmed.length > MaxLength) Left(Error.TooLong(trimmed.length))
    else
      for {
        scheme <- trimmed match {
          case Scheme(s) => Right(s.toLowerCase(Locale.ROOT))
          case _         => Left(Error.Malformed(trimmed))
        }
        _ <- Either.cond(AllowedSchemes(scheme), (), Error.UnsupportedScheme(scheme))
        // Null when the host is missing, the port is invalid, etc.
        url <- Option(HttpUrl.parse(trimmed)).toRight(Error.Malformed(trimmed))
        // user:pass@host is a common phishing trick to disguise the destination, so reject it.
        _ <- Either.cond(
          url.username.isEmpty && url.password.isEmpty,
          (),
          Error.ContainsCredentials
        )
        normalized = url.toString
        // Punycode and percent-encoding can make it longer, so check again after normalization.
        _ <- Either.cond(normalized.length <= MaxLength, (), Error.TooLong(normalized.length))
      } yield Url(normalized)
  }
}
