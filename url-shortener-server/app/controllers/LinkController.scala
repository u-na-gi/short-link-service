package controllers

import javax.inject._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import usecase.{CreateShortLink, CreateShortLinkError}

final case class CreateLinkRequest(url: String)

object CreateLinkRequest {
  // JSON としての形だけを見る。空文字などの業務的な検証は domain.Url が担当する。
  given Reads[CreateLinkRequest] = Json.reads[CreateLinkRequest]
}

@Singleton
class LinkController @Inject() (
    val controllerComponents: ControllerComponents,
    createShortLink: CreateShortLink
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
              val scheme = if (request.secure) "https" else "http"
              Created(
                Json.obj(
                  "code" -> link.code,
                  "shortUrl" -> s"$scheme://${request.host}/${link.code}",
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
          }
      )
  }
}
