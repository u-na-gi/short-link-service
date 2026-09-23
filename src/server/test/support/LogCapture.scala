package support

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{AsyncAppender, Logger => LogbackLogger}
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsValue, Json}
import scala.jdk.CollectionConverters._

object LogCapture {

  /** `run` の間に `loggerName` へ出たログを、conf/logback.xml の encoder (マスクの保険を含む) で JSON に直し、`run`
    * の結果と一緒に返す。 アプリの起動時に logback が設定し直されるので、アプリが起動した後 (テスト本体の中) で呼ぶ。
    */
  def capture[A](loggerName: String)(run: => A): (A, Seq[JsValue]) = {
    val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger]
    val context = logger.getLoggerContext
    val appender = new ListAppender[ILoggingEvent]()
    appender.setContext(context)
    appender.start()
    logger.addAppender(appender)
    val result =
      try run
      finally logger.detachAppender(appender)

    val encoder = context
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      .getAppender("ASYNCSTDOUT")
      .asInstanceOf[AsyncAppender]
      .getAppender("STDOUT")
      .asInstanceOf[ConsoleAppender[ILoggingEvent]]
      .getEncoder
    (result, appender.list.asScala.toSeq.map(event => Json.parse(encoder.encode(event))))
  }
}
