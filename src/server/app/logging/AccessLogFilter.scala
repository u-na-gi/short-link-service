package logging

import javax.inject.{Inject, Singleton}
import net.logstash.logback.marker.Markers
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Writes one access log line per request. Play has no built-in access log, so we have our own.
  *
  * `LogMasking` keeps only the keys of the body and query and hides the values. Only the response's
  * `error` (a fixed error code) and `code` (the short code, which is in the path anyway) show their
  * values, so we can trace what happened. URL fields (`UrlKeys`) are split into parts with only the
  * query values hidden.
  *
  * A request that ends in an exception also gets a line, and the exception is rethrown as-is
  * (ErrorHandler logs the stack trace).
  */
@Singleton
class AccessLogFilter @Inject() ()(using ExecutionContext) extends EssentialFilter {

  private val logger = LoggerFactory.getLogger("access")

  def apply(next: EssentialAction): EssentialAction = EssentialAction { request =>
    val startNanos = System.nanoTime()
    val capture = Option.when(isJson(request.contentType))(BodyCapture())
    val accumulator =
      capture.fold(next(request))(c => next(request).through(Flow[ByteString].map(c.append)))

    accumulator
      .map { result =>
        log(request, startNanos, capture, result.header.status, responseBody(result), None)
        result
      }
      .recoverWith { case e =>
        log(request, startNanos, capture, 500, None, Some(e))
        Future.failed(e)
      }
  }

  private def log(
      request: RequestHeader,
      startNanos: Long,
      capture: Option[BodyCapture],
      status: Int,
      responseBody: Option[AnyRef],
      exception: Option[Throwable]
  ): Unit = {
    val elapsedMs = (System.nanoTime() - startNanos) / 1000000
    val fields = new java.util.LinkedHashMap[String, AnyRef]()
    RequestId.of(request).foreach(fields.put("requestId", _))
    fields.put("method", request.method)
    // The Cloudflare Worker in production and the Vite proxy in compose rewrite Host, so the original Host is in X-Forwarded-Host.
    // The sender can set this header freely, so use it only for logging.
    fields.put("host", request.headers.get("X-Forwarded-Host").getOrElse(request.host))
    fields.put("path", request.path)
    if (request.queryString.nonEmpty) {
      val query = new java.util.LinkedHashMap[String, AnyRef]()
      request.queryString.foreach {
        case (key, Seq(value)) if AccessLogFilter.UrlKeys(key) =>
          query.put(key, LogMasking.toJava(LogMasking.urlSummary(value)))
        case (key, _) => query.put(key, LogMasking.Mask)
      }
      fields.put("query", query)
    }
    fields.put("status", Int.box(status))
    fields.put("elapsedMs", Long.box(elapsedMs))
    capture.flatMap(_.summary).foreach(fields.put("requestBody", _))
    responseBody.foreach(fields.put("responseBody", _))
    exception.foreach(e => fields.put("exception", e.getClass.getName))

    val marker = Markers.appendEntries(fields)
    val message = s"${request.method} ${request.path} $status ${elapsedMs}ms"
    // Health checks come regularly and would flood the log, so they are hidden normally
    if (AccessLogFilter.HealthCheckPaths(request.path)) logger.debug(marker, message)
    else logger.info(marker, message)
  }

  private def isJson(contentType: Option[String]): Boolean =
    contentType.exists(_.endsWith("json"))

  private def responseBody(result: Result): Option[AnyRef] =
    result.body match {
      case HttpEntity.Strict(data, contentType) if isJson(contentType) && data.nonEmpty =>
        Try(Json.parse(data.toArray)).toOption
          .map(json =>
            LogMasking.toJava(
              LogMasking.mask(json, AccessLogFilter.RevealedResponseKeys, AccessLogFilter.UrlKeys)
            )
          )
      case _ => None
    }

  /** Taps the streaming request body and keeps only the beginning for the log. */
  private final class BodyCapture {
    @volatile private var bytes = ByteString.empty
    @volatile private var total = 0L

    def append(chunk: ByteString): ByteString = {
      total += chunk.length
      if (bytes.length < AccessLogFilter.MaxCapturedBytes)
        bytes = bytes ++ chunk.take(AccessLogFilter.MaxCapturedBytes - bytes.length)
      chunk
    }

    def summary: Option[AnyRef] =
      if (total == 0) None
      else if (total > AccessLogFilter.MaxCapturedBytes) Some(s"<$total bytes, omitted: too large>")
      else
        Some(
          Try(Json.parse(bytes.toArray)).toOption
            .map(json => LogMasking.toJava(LogMasking.mask(json, urls = AccessLogFilter.UrlKeys)))
            .getOrElse(s"<$total bytes, not valid JSON>")
        )
  }
}

object AccessLogFilter {
  val HealthCheckPaths: Set[String] = Set("/", "/api/v1/health")
  val RevealedResponseKeys: Set[String] = Set("error", "code")

  /** `url` in the create body, `shortUrl` in the resolve query, `shortUrl` / `originalUrl` in
    * responses
    */
  val UrlKeys: Set[String] = Set("url", "shortUrl", "originalUrl")

  /** URLs are at most 2048 characters, so a valid body fits in this */
  val MaxCapturedBytes = 8 * 1024
}
