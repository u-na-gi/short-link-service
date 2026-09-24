package controllers

import domain.{PublicBaseUrl, ShortLink, Url}
import javax.inject._
import logging.RequestLog
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import usecase.{CreateShortLink, CreateShortLinkError, ResolveShortLink, ResolveShortLinkError}

final case class CreateLinkRequest(url: String)

object CreateLinkRequest {
  // JSON としての形だけを見る。空文字などの業務的な検証は domain.Url が担当する。
  given Reads[CreateLinkRequest] = Json.reads[CreateLinkRequest]
}

@Singleton
class LinkController @Inject() (
    val controllerComponents: ControllerComponents,
    createShortLink: CreateShortLink,
    resolveShortLink: ResolveShortLink,
    publicBaseUrl: PublicBaseUrl
)(implicit ec: ExecutionContext)
    extends BaseController {
  import LinkController._

  private val logger = Logger(getClass)

  def create() = Action.async(parse.json) { request =>
    request.body
      .validate[CreateLinkRequest]
      .fold(
        errors => {
          // どの項目がどう不正かは JSON の構造の話で、値は含まない。利用者には返さず調査用に残す
          logger.debug(s"invalid request body: ${JsError.toJson(errors)}")(using
            RequestLog.marker(request)
          )
          Future.successful(BadRequest(errorJson("invalid_request")))
        },
        body =>
          createShortLink.execute(body.url).map {
            case Right(link)                                  => Created(linkJson(link))
            case Left(CreateShortLinkError.InvalidUrl(cause)) =>
              // 原因ごとに案内を変えられるよう、種類だけ返す。入力値や上限などの詳細は返さない
              BadRequest(errorJson("invalid_url") + ("reason" -> JsString(invalidUrlReason(cause))))
            case Left(CreateShortLinkError.SelfReference(_)) =>
              BadRequest(errorJson("self_reference"))
            case Left(CreateShortLinkError.CodeExhausted) =>
              // 例外ではないので ErrorHandler を通らない。500 の原因をここで残す
              logger.error("gave up issuing a short code: retry limit reached")(using
                RequestLog.marker(request)
              )
              InternalServerError(errorJson("code_generation_failed"))
            case Left(CreateShortLinkError.StorageFull) =>
              // 上限 (shortener.max-links) を上げるか再起動しない限り続くので、運用で気づけるよう残す
              logger.warn("refused to issue a short code: storage is full")(using
                RequestLog.marker(request)
              )
              ServiceUnavailable(errorJson("storage_full"))
          }
      )
  }

  /** 貼り付けられた短縮URLから元URLを返す。短縮URLを丸ごと受け取り、コードの取り出しはサーバで行う。 公開 URL (`shortener.base-url`)
    * を知っているのはサーバだけなので。
    */
  def resolve(shortUrl: Option[String]) = Action.async {
    shortUrl match {
      case None      => Future.successful(BadRequest(errorJson("invalid_request")))
      case Some(raw) =>
        resolveShortLink.fromShortUrl(raw).map {
          case Right(link)                             => Ok(linkJson(link))
          case Left(ResolveShortLinkError.NotShortUrl) => BadRequest(errorJson("not_short_url"))
          case Left(ResolveShortLinkError.NotFound)    => NotFound(errorJson("not_found"))
        }
    }
  }

  /** 作成と復元で同じ形を返し、フロントが 1 つの型で扱えるようにする。 */
  private def linkJson(link: ShortLink): JsObject =
    Json.obj(
      "code" -> link.code,
      "shortUrl" -> publicBaseUrl.linkTo(link.code),
      "originalUrl" -> link.url.value
    )

  /** 短縮URLへのアクセスを元URLへ飛ばす。Play の Redirect は既定が 303 なので 302 を明示する。 */
  def redirect(code: String) = Action.async {
    resolveShortLink.execute(code).map {
      case Some(link) => Redirect(link.url.value, FOUND)
      case None       => NotFound(errorJson("not_found"))
    }
  }
}

object LinkController {

  /** 利用者向けの文言はフロントがエラーコードから組み立てる。入力値や内部の事情を返さないよう、コードだけにする。 */
  private def errorJson(code: String): JsObject = Json.obj("error" -> code)

  /** フロントの `UrlProblem` (src/front/src/url.ts) と値を揃える。 */
  private def invalidUrlReason(error: Url.Error): String = error match {
    case Url.Error.Empty                => "empty"
    case Url.Error.Malformed(_)         => "malformed"
    case Url.Error.UnsupportedScheme(_) => "unsupported_scheme"
    case Url.Error.ContainsCredentials  => "credentials"
    case Url.Error.TooLong(_)           => "too_long"
  }
}
