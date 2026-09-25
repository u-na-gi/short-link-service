package controllers

import javax.inject.Singleton
import logging.RequestLog
import play.api.Logger
import play.api.http.HttpErrorHandler
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc.{RequestHeader, Result}
import scala.concurrent.Future

/** Errors that Play itself returns (broken JSON, a Host that is not allowed, unhandled exceptions,
  * etc.) also use the same `{"error": ...}` shape as the API. The default handler returns HTML in
  * prod mode.
  *
  * Details (Play's message or the exception) go only to the server log, in English. Users get only
  * the error code.
  */
@Singleton
class ErrorHandler extends HttpErrorHandler {

  private val logger = Logger(getClass)

  override def onClientError(
      request: RequestHeader,
      statusCode: Int,
      message: String
  ): Future[Result] = {
    // message can contain fragments of the sent body, such as Jackson parse errors, so do not return it; keep it at DEBUG.
    // The status shows up in the access log.
    logger.debug(s"client error $statusCode: $message")(using RequestLog.marker(request))
    Future.successful(Status(statusCode)(Json.obj("error" -> clientErrorCode(statusCode))))
  }

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    logger.error(s"unhandled exception in ${request.method} ${request.path}", exception)(using
      RequestLog.marker(request)
    )
    // Exception messages contain internal details, so do not return them to users
    Future.successful(InternalServerError(Json.obj("error" -> "internal_error")))
  }

  private def clientErrorCode(statusCode: Int): String =
    statusCode match {
      case BAD_REQUEST              => "invalid_request"
      case FORBIDDEN                => "forbidden"
      case NOT_FOUND                => "not_found"
      case REQUEST_ENTITY_TOO_LARGE => "payload_too_large"
      case UNSUPPORTED_MEDIA_TYPE   => "unsupported_media_type"
      case _                        => "client_error"
    }
}
