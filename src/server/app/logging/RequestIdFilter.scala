package logging

import java.util.UUID
import javax.inject.{Inject, Singleton}
import play.api.http.HttpErrorHandler
import play.api.libs.typedmap.TypedKey
import play.api.mvc._
import scala.concurrent.ExecutionContext

/** リクエストごとに ID を振り、リクエストの属性に入れてレスポンスの `X-Request-Id` で返す。
  *
  * Play の `request.id` はプロセス内の連番で、再起動のたびに 1 から振り直されるので、溜まったログの中で一意にならない。 送られてきた `X-Request-Id`
  * は使わない。誰でも付けられるので、別のリクエストと同じ ID にされうる。
  *
  * action で起きた例外は、ここで ID 付きのリクエストとして ErrorHandler に渡す。そのまま上に投げると Play は属性の無い元の リクエストで ErrorHandler
  * を呼ぶので、スタックトレースのログに ID が付かない。
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

  /** RequestIdFilter を通っていないリクエスト (テストで直接呼ぶときなど) では None */
  def of(request: RequestHeader): Option[String] = request.attrs.get(Key)
}
