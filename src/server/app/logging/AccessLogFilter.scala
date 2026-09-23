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

/** 1 リクエストにつき 1 行のアクセスログを出す。Play には標準のアクセスログが無いので自前で持つ。
  *
  * body とクエリは `LogMasking` でキーだけ残して値を隠す。レスポンスの `error` (固定のエラーコード) と `code` (どうせパスに出る短縮コード)
  * だけは、何が起きたか追えるよう値を出す。
  *
  * 例外で終わったリクエストも 1 行出して、例外はそのまま上へ投げ直す (スタックトレースは ErrorHandler が出す)。
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
    // 本番は CloudFront、compose では Vite のプロキシが Host を書き換えるので、元の Host は X-Forwarded-Host にある。
    // 送り手が自由に付けられるヘッダなので、記録にだけ使う。
    fields.put("host", request.headers.get("X-Forwarded-Host").getOrElse(request.host))
    fields.put("path", request.path)
    if (request.queryString.nonEmpty) {
      val query = new java.util.LinkedHashMap[String, AnyRef]()
      request.queryString.keys.foreach(key => query.put(key, LogMasking.Mask))
      fields.put("query", query)
    }
    fields.put("status", Int.box(status))
    fields.put("elapsedMs", Long.box(elapsedMs))
    capture.flatMap(_.summary).foreach(fields.put("requestBody", _))
    responseBody.foreach(fields.put("responseBody", _))
    exception.foreach(e => fields.put("exception", e.getClass.getName))

    val marker = Markers.appendEntries(fields)
    val message = s"${request.method} ${request.path} $status ${elapsedMs}ms"
    // ヘルスチェックは定期的に来てログが埋まるので、普段は出さない
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
            LogMasking.toJava(LogMasking.mask(json, AccessLogFilter.RevealedResponseKeys))
          )
      case _ => None
    }

  /** 流れてくる request body を横から覗いて、ログ用に先頭だけ取っておく。 */
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
            .map(json => LogMasking.toJava(LogMasking.mask(json)))
            .getOrElse(s"<$total bytes, not valid JSON>")
        )
  }
}

object AccessLogFilter {
  val HealthCheckPaths: Set[String] = Set("/", "/api/v1/health")
  val RevealedResponseKeys: Set[String] = Set("error", "code")

  /** URL は 2048 文字までなので、正常な body はこれに収まる */
  val MaxCapturedBytes = 8 * 1024
}
