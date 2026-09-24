package domain

import java.net.{URI, URISyntaxException}

/** 利用者に見せる自サービスの公開 URL（スキーム + ホスト [+ ポート]、末尾スラッシュなし）。
  *
  * 短縮 URL は Host ヘッダではなく必ずこの値から組み立てる。
  *
  * 本番は Cloudflare Worker と Tunnel を挟むので Host は localhost:9000 になり、TLS 終端の後ろではスキームも http に見える。
  *
  * そもそも Host ヘッダはクライアントが偽装できる。
  */
final case class PublicBaseUrl private (value: String) {

  /** 自己参照の判定に使うホスト名。設定の出どころを 1 つにして、短縮 URL の向き先と判定が食い違わないようにする。 */
  def host: ServiceHost = ServiceHost(URI(value).getHost)

  def linkTo(code: String): String = s"$value/$code"

  /** 自サービスの短縮 URL ならコードを取り出す。
    *
    * ホスト名だけで判定し、スキームとポートは問わない。自己参照の判定 ([[ServiceHost]]) と基準を揃えるため。
    *
    * ずれると「短縮はできないのに、戻すこともできない」URL ができる。
    *
    * クエリとフラグメントは、リダイレクト (`GET /:code`) でも無視されるので同じく無視する。
    */
  def codeOf(raw: String): Option[String] =
    Url
      .from(raw)
      .toOption
      .filter(url => host.matches(url.host))
      .collect { case url if PublicBaseUrl.CodePath.matches(url.path) => url.path.drop(1) }
}

object PublicBaseUrl {

  /** 採番するコード (英数 8 文字) だけを 1 階層のパスとして受け付ける。 */
  private val CodePath = "/[A-Za-z0-9]{8}".r

  /** 設定ミスは起動時に止めるだけで利用者には見せないので、文言は持たせず呼び出し側 (Module) で組み立てる。 */
  enum Error {

    /** stack trace を残せるよう、パースの例外をそのまま持つ。 */
    case Malformed(cause: URISyntaxException)
    case UnsupportedScheme(scheme: Option[String])
    case MissingHost
    case HasUserInfo
    case HasQuery
    case HasFragment

    /** 短縮 URL は /{code} で Play のルートに当たる前提なので、パス付きの公開 URL は扱えない。 */
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
