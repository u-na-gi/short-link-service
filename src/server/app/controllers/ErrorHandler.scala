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

/** Play 自身が返すエラー (壊れた JSON・許可されない Host・未処理の例外など) も、API と同じ `{"error": ...}` の形で返す。 既定のハンドラは本番モードで
  * HTML を返してしまう。
  *
  * 詳細 (Play のメッセージや例外) はサーバのログにだけ英語で出し、利用者にはエラーコードだけ返す。
  */
@Singleton
class ErrorHandler extends HttpErrorHandler {

  private val logger = Logger(getClass)

  override def onClientError(
      request: RequestHeader,
      statusCode: Int,
      message: String
  ): Future[Result] = {
    // message には Jackson のパースエラーなど、送られた body の断片が入ることがあるので、利用者に返さず DEBUG に留める。
    // ステータスはアクセスログに出る。
    logger.debug(s"client error $statusCode: $message")(using RequestLog.marker(request))
    Future.successful(Status(statusCode)(Json.obj("error" -> clientErrorCode(statusCode))))
  }

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    logger.error(s"unhandled exception in ${request.method} ${request.path}", exception)(using
      RequestLog.marker(request)
    )
    // 例外のメッセージには内部の事情が入るので、利用者には返さない
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
