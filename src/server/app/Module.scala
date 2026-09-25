import com.google.inject.{AbstractModule, Provides}
import domain.{PublicBaseUrl, ServiceHost, ShortLinkRepository, ShortLinkService}
import infra.inmemory.{InMemoryShortLinkRepository, LinkCapacity}
import javax.inject.Singleton
import play.api.Configuration
import service.DefaultShortLinkService

/** Play loads `Module` in the root package automatically. Bindings from traits to implementations
  * live here.
  */
class Module extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[ShortLinkRepository]).to(classOf[InMemoryShortLinkRepository])
    bind(classOf[ShortLinkService]).to(classOf[DefaultShortLinkService])
  }

  /** Reading the config is kept here so usecases do not depend on Play's Configuration. If the
    * config is broken, we want to stop startup before taking requests, so validate it here.
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
            // Keep the original exception as the cause on a parse failure, so the stack trace shows the reason.
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

  /** Only shown in the server log (startup failure), so written in English. */
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
