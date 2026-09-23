package controllers

import domain.PublicBaseUrl
import javax.inject._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import usecase.{CreateShortLink, CreateShortLinkError, ResolveShortLink}

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

  def create() = Action.async(parse.json) { request =>
    request.body
      .validate[CreateLinkRequest]
      .fold(
        errors =>
          Future.successful(
            BadRequest(
              Json.obj(
                "error" -> "invalid_request",
                "details" -> JsError.toJson(errors)
              )
            )
          ),
        body =>
          createShortLink.execute(body.url).map {
            case Right(link) =>
              Created(
                Json.obj(
                  "code" -> link.code,
                  "shortUrl" -> publicBaseUrl.linkTo(link.code),
                  "originalUrl" -> link.url.value
                )
              )
            case Left(CreateShortLinkError.InvalidUrl(cause)) =>
              BadRequest(
                Json.obj(
                  "error" -> "invalid_url",
                  "message" -> cause.message
                )
              )
            case Left(CreateShortLinkError.SelfReference(host)) =>
              BadRequest(
                Json.obj(
                  "error" -> "self_reference",
                  "message" -> s"$host 宛の url は短縮できません"
                )
              )
            case Left(CreateShortLinkError.CodeExhausted) =>
              InternalServerError(
                Json.obj(
                  "error" -> "code_generation_failed",
                  "message" -> "短縮コードを採番できませんでした"
                )
              )
          }
      )
  }

  /** 短縮URLへのアクセスを元URLへ飛ばす。Play の Redirect は既定が 303 なので 302 を明示する。 */
  def redirect(code: String) = Action.async {
    resolveShortLink.execute(code).map {
      case Some(link) => Redirect(link.url.value, FOUND)
      case None       =>
        NotFound(
          Json.obj(
            "error" -> "not_found",
            "message" -> s"短縮URLが見つかりません: $code"
          )
        )
    }
  }
}
