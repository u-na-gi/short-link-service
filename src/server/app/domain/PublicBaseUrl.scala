package domain

import java.net.{URI, URISyntaxException}

/** Public URL of this service shown to users (scheme + host [+ port], no trailing slash).
  *
  * Short URLs are always built from this value, never from the Host header.
  *
  * In production, the Cloudflare Worker and Tunnel sit in between, so Host is localhost:9000, and
  * behind TLS termination the scheme also looks like http.
  *
  * Besides, clients can spoof the Host header.
  */
final case class PublicBaseUrl private (value: String) {

  /** Host name used for the self-reference check. One config source keeps it in line with where
    * short URLs point.
    */
  def host: ServiceHost = ServiceHost(URI(value).getHost)

  def linkTo(code: String): String = s"$value/$code"

  /** Extracts the code if the URL is a short URL of this service.
    *
    * Only the host name is checked; scheme and port do not matter. This keeps the same rule as the
    * self-reference check ([[ServiceHost]]).
    *
    * If they differed, some URLs could be neither shortened nor resolved.
    *
    * Query and fragment are ignored, as the redirect (`GET /:code`) also ignores them.
    */
  def codeOf(raw: String): Option[String] =
    Url
      .from(raw)
      .toOption
      .filter(url => host.matches(url.host))
      .collect { case url if PublicBaseUrl.CodePath.matches(url.path) => url.path.drop(1) }
}

object PublicBaseUrl {

  /** Accept only an issued code (8 alphanumeric characters) as a single path segment. */
  private val CodePath = "/[A-Za-z0-9]{8}".r

  /** Config mistakes only stop startup and are never shown to users, so no messages here; the
    * caller (Module) builds them.
    */
  enum Error {

    /** Keeps the parse exception as-is, so the stack trace is not lost. */
    case Malformed(cause: URISyntaxException)
    case UnsupportedScheme(scheme: Option[String])
    case MissingHost
    case HasUserInfo
    case HasQuery
    case HasFragment

    /** Short URLs assume /{code} hits the Play route, so a public URL with a path won't work. */
    case HasPath(path: String)
  }

  def from(raw: String): Either[Error, PublicBaseUrl] =
    try {
      val uri = URI(raw.trim)
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      if (!scheme.contains("http") && !scheme.contains("https"))
        Left(Error.UnsupportedScheme(scheme))
      else if (uri.getHost == null) Left(Error.MissingHost)
      else if (uri.getRawUserInfo != null) Left(Error.HasUserInfo)
      else if (uri.getRawQuery != null) Left(Error.HasQuery)
      else if (uri.getRawFragment != null) Left(Error.HasFragment)
      else if (Option(uri.getRawPath).exists(p => p.nonEmpty && p != "/"))
        Left(Error.HasPath(uri.getRawPath))
      else {
        val port = if (uri.getPort == -1) "" else s":${uri.getPort}"
        Right(PublicBaseUrl(s"${scheme.get}://${uri.getHost.toLowerCase}$port"))
      }
    } catch {
      case e: URISyntaxException => Left(Error.Malformed(e))
    }
}
