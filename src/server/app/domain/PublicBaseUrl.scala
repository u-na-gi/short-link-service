package domain

import java.net.{URI, URISyntaxException}

/** 利用者に見せる自サービスの公開 URL（スキーム + ホスト [+ ポート]、末尾スラッシュなし）。
  *
  * 短縮 URL は Host ヘッダではなく必ずこの値から組み立てる。
  *
  * CloudFront を挟むと Host は origin 側の名前になり、TLS 終端の後ろではスキームも http に見える。
  *
  * そもそも Host ヘッダはクライアントが偽装できる。
  */
final case class PublicBaseUrl private (value: String) {

  /** 自己参照の判定に使うホスト名。設定の出どころを 1 つにして、短縮 URL の向き先と判定が食い違わないようにする。 */
  def host: ServiceHost = ServiceHost(URI(value).getHost)

  def linkTo(code: String): String = s"$value/$code"
}

object PublicBaseUrl {

  def from(raw: String): Either[String, PublicBaseUrl] =
    try {
      val uri = URI(raw.trim)
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      if (!scheme.contains("http") && !scheme.contains("https"))
        Left(s"http か https の URL を指定してください: $raw")
      else if (uri.getHost == null)
        Left(s"ホスト名がありません: $raw")
      else if (uri.getRawUserInfo != null || uri.getRawQuery != null || uri.getRawFragment != null)
        Left(s"認証情報・クエリ・フラグメントは付けられません: $raw")
      else if (Option(uri.getRawPath).exists(p => p.nonEmpty && p != "/"))
        // 短縮 URL は /{code} で Play のルートに当たる前提なので、パス付きの公開 URL は扱えない。
        Left(s"パスは付けられません: $raw")
      else {
        val port = if (uri.getPort == -1) "" else s":${uri.getPort}"
        Right(PublicBaseUrl(s"${scheme.get}://${uri.getHost.toLowerCase}$port"))
      }
    } catch {
      case e: URISyntaxException => Left(s"URL として解釈できません: $raw (${e.getMessage})")
    }
}
