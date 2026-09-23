import com.google.inject.{AbstractModule, Provides}
import domain.{ServiceHost, ShortLinkRepository, ShortLinkService}
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

  /** 設定の読み出しをここに閉じ込め、ユースケースが Play の Configuration に依存しないようにする。 */
  @Provides
  @Singleton
  def serviceHost(configuration: Configuration): ServiceHost =
    ServiceHost(configuration.get[String]("shortener.host"))
}
