package logging

import java.util.UUID
import javax.inject.{Inject, Singleton}
import play.api.http.HttpErrorHandler
import play.api.libs.typedmap.TypedKey
import play.api.mvc._
import scala.concurrent.ExecutionContext

/** Assigns an ID to each request, stores it in a request attribute, and returns it in the
  * `X-Request-Id` response header.
  *
  * Play's `request.id` is an in-process counter that restarts from 1 on every restart, so it is not
  * unique across collected logs. An incoming `X-Request-Id` is not used: anyone can set it, so it
  * could be made the same as another request's ID.
  *
  * Exceptions from the action are passed to ErrorHandler here with the request that has the ID. If
  * rethrown as-is, Play calls ErrorHandler with the original request without the attribute, and the
  * stack trace log gets no ID.
  */
@Singleton
class RequestIdFilter @Inject() (errorHandler: HttpErrorHandler)(using ExecutionContext)
    extends EssentialFilter {

  def apply(next: EssentialAction): EssentialAction = EssentialAction { request =>
    val id = UUID.randomUUID().toString
    val withId = request.addAttr(RequestId.Key, id)
    next(withId)
      .recoverWith { case e => errorHandler.onServerError(withId, e) }
      .map(_.withHeaders(RequestId.Header -> id))
  }
}

object RequestId {
  val Key: TypedKey[String] = TypedKey("requestId")
  val Header = "X-Request-Id"

  /** None for requests that skipped RequestIdFilter (e.g. when called directly in tests) */
  def of(request: RequestHeader): Option[String] = request.attrs.get(Key)
}
