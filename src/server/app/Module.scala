import com.google.inject.{AbstractModule, Provides}
import domain.{PublicBaseUrl, ServiceHost, ShortLinkRepository, ShortLinkService}
import infra.inmemory.{InMemoryShortLinkRepository, LinkCapacity}
import javax.inject.Singleton
import play.api.Configuration
import service.DefaultShortLinkService

/** ルートパッケージの `Module` は Play が自動で読み込む。trait と実装の紐付けはここに集約する。 */
class Module extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[ShortLinkRepository]).to(classOf[InMemoryShortLinkRepository])
    bind(classOf[ShortLinkService]).to(classOf[DefaultShortLinkService])
  }

  /** 設定の読み出しをここに閉じ込め、ユースケースが Play の Configuration に依存しないようにする。
    * 設定が壊れていたらリクエストを受ける前に起動を止めたいので、ここで検証する。
    */
  @Provides
  @Singleton
  def publicBaseUrl(configuration: Configuration): PublicBaseUrl =
    PublicBaseUrl
      .from(configuration.get[String]("shortener.base-url"))
      .fold(
        error =>
          throw configuration.reportError(
            "shortener.base-url",
            Module.describe(error),
            // パースの失敗は元の例外を cause に付け、stack trace から原因を追えるようにする。
            PartialFunction.condOpt(error) { case PublicBaseUrl.Error.Malformed(cause) => cause }
          ),
        identity
      )

  @Provides
  @Singleton
  def serviceHost(baseUrl: PublicBaseUrl): ServiceHost = baseUrl.host

  @Provides
  @Singleton
  def linkCapacity(configuration: Configuration): LinkCapacity = {
    val maxLinks = configuration.get[Int]("shortener.max-links")
    if (maxLinks <= 0)
      throw configuration.reportError("shortener.max-links", s"must be positive (got: $maxLinks)")
    LinkCapacity(maxLinks)
  }
}

object Module {

  /** サーバのログにだけ出る (起動失敗) ので英語で書く。 */
  private def describe(error: PublicBaseUrl.Error): String = error match {
    case PublicBaseUrl.Error.Malformed(cause)          => s"not a valid URL: ${cause.getMessage}"
    case PublicBaseUrl.Error.UnsupportedScheme(scheme) =>
      s"scheme must be http or https (got: ${scheme.getOrElse("none")})"
    case PublicBaseUrl.Error.MissingHost   => "host is missing"
    case PublicBaseUrl.Error.HasUserInfo   => "user info is not allowed"
    case PublicBaseUrl.Error.HasQuery      => "query is not allowed"
    case PublicBaseUrl.Error.HasFragment   => "fragment is not allowed"
    case PublicBaseUrl.Error.HasPath(path) =>
      s"path is not allowed (got: $path); short URLs are served at /{code}"
  }
}
