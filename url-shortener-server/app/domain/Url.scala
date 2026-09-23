package domain

import okhttp3.HttpUrl

import java.util.Locale

/** 検証済みの URL を表す値オブジェクト。
  *
  * コンストラクタは private なので、生成は必ず [[Url.from]] を経由する。 これにより「不正な値を持つ Url インスタンスは存在しない」ことを型で保証している。
  */
final case class Url private (value: String) {

  /** 検証済みなのでパースは必ず成功し、ホストも正規化済み。 */
  def host: String = HttpUrl.get(value).host
}

object Url {

  enum Error {
    case Empty
    case Malformed(raw: String)
    case UnsupportedScheme(scheme: String)
    case ContainsCredentials
    case TooLong(length: Int)

    def message: String = this match {
      case Empty                => "url が空です"
      case Malformed(raw)       => s"url として解釈できません: $raw"
      case UnsupportedScheme(s) => s"サポートしていないスキームです: $s"
      case ContainsCredentials  => "認証情報を含む url は登録できません"
      case TooLong(length)      => s"url が長すぎます ($length 文字 / 上限 $MaxLength 文字)"
    }
  }

  /** javascript: や data: を弾くため、許可するスキームはホワイトリストで持つ。 */
  private val AllowedSchemes = Set("http", "https")

  /** 主要ブラウザの実用上限に合わせた値。 */
  private val MaxLength = 2048

  /** HttpUrl は http/https 以外を null で返すだけなので、エラーを出し分けるためにスキームは先に取り出す。 */
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
        // ホストが無い・ポートが不正などは null になる。
        url <- Option(HttpUrl.parse(trimmed)).toRight(Error.Malformed(trimmed))
        // user:pass@host は行き先を誤認させるフィッシングの常套手段なので拒否する。
        _ <- Either.cond(
          url.username.isEmpty && url.password.isEmpty,
          (),
          Error.ContainsCredentials
        )
        normalized = url.toString
        // punycode 化やパーセントエンコードで伸びることがあるので、正規化後にも確認する。
        _ <- Either.cond(normalized.length <= MaxLength, (), Error.TooLong(normalized.length))
      } yield Url(normalized)
  }
}
