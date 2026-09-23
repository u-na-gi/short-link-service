import com.google.inject.{AbstractModule, Provides}
import domain.{PublicBaseUrl, ServiceHost, ShortLinkRepository, ShortLinkService}
import infra.inmemory.InMemoryShortLinkRepository
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
      .fold(message => throw configuration.reportError("shortener.base-url", message), identity)

  @Provides
  @Singleton
  def serviceHost(baseUrl: PublicBaseUrl): ServiceHost = baseUrl.host
}
