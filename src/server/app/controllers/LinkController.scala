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
  // Only checks the JSON shape. Business validation such as empty strings is done by domain.Url.
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
          // Which field is wrong and how is about the JSON structure, with no values. Keep it for debugging, not for users
          logger.debug(s"invalid request body: ${JsError.toJson(errors)}")(using
            RequestLog.marker(request)
          )
          Future.successful(BadRequest(errorJson("invalid_request")))
        },
        body =>
          createShortLink.execute(body.url).map {
            case Right(link)                                  => Created(linkJson(link))
            case Left(CreateShortLinkError.InvalidUrl(cause)) =>
              // Return only the kind, so guidance can differ by cause. Do not return details such as the input or limits
              BadRequest(errorJson("invalid_url") + ("reason" -> JsString(invalidUrlReason(cause))))
            case Left(CreateShortLinkError.SelfReference(_)) =>
              BadRequest(errorJson("self_reference"))
            case Left(CreateShortLinkError.CodeExhausted) =>
              // Not an exception, so it does not go through ErrorHandler. Log the cause of the 500 here
              logger.error("gave up issuing a short code: retry limit reached")(using
                RequestLog.marker(request)
              )
              InternalServerError(errorJson("code_generation_failed"))
            case Left(CreateShortLinkError.StorageFull) =>
              // This continues until the limit (shortener.max-links) is raised or the server restarts, so log it for operators
              logger.warn("refused to issue a short code: storage is full")(using
                RequestLog.marker(request)
              )
              ServiceUnavailable(errorJson("storage_full"))
          }
      )
  }

  /** Returns the original URL for a pasted short URL. Takes the whole short URL and extracts the
    * code on the server, because only the server knows the public URL (`shortener.base-url`).
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

  /** Create and resolve return the same shape, so the front end can use one type. */
  private def linkJson(link: ShortLink): JsObject =
    Json.obj(
      "code" -> link.code,
      "shortUrl" -> publicBaseUrl.linkTo(link.code),
      "originalUrl" -> link.url.value
    )

  /** Redirects a short URL to the original URL. Play's Redirect defaults to 303, so set 302
    * explicitly.
    */
  def redirect(code: String) = Action.async {
    resolveShortLink.execute(code).map {
      case Some(link) => Redirect(link.url.value, FOUND)
      case None       => NotFound(errorJson("not_found"))
    }
  }
}

object LinkController {

  /** The front end builds user-facing messages from the error code. Return only the code, with no
    * input or internal details.
    */
  private def errorJson(code: String): JsObject = Json.obj("error" -> code)

  /** Values match the front end's `UrlProblem` (src/front/src/url.ts). */
  private def invalidUrlReason(error: Url.Error): String = error match {
    case Url.Error.Empty                => "empty"
    case Url.Error.Malformed(_)         => "malformed"
    case Url.Error.UnsupportedScheme(_) => "unsupported_scheme"
    case Url.Error.ContainsCredentials  => "credentials"
    case Url.Error.TooLong(_)           => "too_long"
  }
}
