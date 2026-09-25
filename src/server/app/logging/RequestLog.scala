package logging

import net.logstash.logback.marker.Markers
import play.api.MarkerContext
import play.api.mvc.RequestHeader

object RequestLog {

  /** Adds the same requestId as the access log (assigned by RequestIdFilter), so logs for one
    * request can be matched up. Play crosses threads with Futures and MDC is not carried over, so
    * pass it explicitly on each log.
    */
  def marker(request: RequestHeader): MarkerContext =
    RequestId
      .of(request)
      .fold(MarkerContext.NoMarker)(id => MarkerContext(Markers.append("requestId", id)))
}
