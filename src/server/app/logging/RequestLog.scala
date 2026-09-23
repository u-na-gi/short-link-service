package logging

import net.logstash.logback.marker.Markers
import play.api.MarkerContext
import play.api.mvc.RequestHeader

object RequestLog {

  /** アクセスログと同じ requestId (RequestIdFilter が振る) をログに付け、1 リクエスト分を突き合わせられるようにする。 Play は Future
    * でスレッドをまたぐので MDC は引き継がれず、ログごとに明示的に渡す。
    */
  def marker(request: RequestHeader): MarkerContext =
    RequestId
      .of(request)
      .fold(MarkerContext.NoMarker)(id => MarkerContext(Markers.append("requestId", id)))
}
