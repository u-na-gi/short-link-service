package support

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{AsyncAppender, Logger => LogbackLogger}
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsValue, Json}
import scala.jdk.CollectionConverters._

object LogCapture {

  /** Converts logs written to `loggerName` during `run` to JSON with the encoder from
    * conf/logback.xml (including the masking safety net), and returns them with the result of
    * `run`. Logback is reconfigured when the app starts, so call this after the app has started
    * (inside the test body).
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
