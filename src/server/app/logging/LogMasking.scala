package logging

import okhttp3.HttpUrl
import play.api.libs.json._

/** Hides the values in JSON written to logs. Keeps only the keys (which fields were sent), not the
  * values.
  *
  * The original URL's query may contain signed URLs or password reset tokens, so instead of listing
  * keys to hide, we list only the keys that may be shown. New fields are hidden by default.
  *
  * Only URL fields are split into parts, so we can trace what was sent ([[urlSummary]]).
  */
object LogMasking {

  val Mask = "***"

  /** Paths are cut at this length. URLs can be 2048 characters, which makes logs hard to read if
    * written in full
    */
  val MaxPathLength = 256

  /** Replaces all values with `Mask`. Keys in `revealed` are shown as-is only when the value is a
    * string, number, or boolean. Keys in `urls` become [[urlSummary]] when the value is a string.
    */
  def mask(
      value: JsValue,
      revealed: Set[String] = Set.empty,
      urls: Set[String] = Set.empty
  ): JsValue =
    value match {
      case JsObject(fields) =>
        JsObject(fields.map {
          case (key, JsString(s)) if urls(key) => key -> urlSummary(s)
          case (key, v @ (_: JsString | _: JsNumber | _: JsBoolean)) if revealed(key) => key -> v
          case (key, v)                                                               =>
            key -> mask(v, revealed, urls)
        })
      case JsArray(items) => JsArray(items.map(mask(_, revealed, urls)))
      case _              => JsString(Mask)
    }

  /** Splits a URL into scheme, host, port (when not the default), path, and query keys.
    *
    * Tokens and signatures are usually in the query, so the query shows only keys with values
    * hidden (duplicates merged). The fragment can also hold OAuth tokens, so only its presence is
    * shown. User info (user:pass@) is not written. Anything that cannot be read as http / https is
    * hidden entirely.
    */
  def urlSummary(raw: String): JsValue =
    Option(HttpUrl.parse(raw.trim)).fold[JsValue](JsString(Mask)) { url =>
      val path = url.encodedPath
      JsObject(
        Seq("scheme" -> JsString(url.scheme), "host" -> JsString(url.host)) ++
          Option.when(url.port != HttpUrl.defaultPort(url.scheme))("port" -> JsNumber(url.port)) ++
          Seq(
            "path" -> JsString(
              if (path.length > MaxPathLength) path.take(MaxPathLength) + "..." else path
            )
          ) ++
          Option.when(url.querySize > 0)("query" -> JsArray(queryKeys(url).map(JsString(_)))) ++
          Option(url.encodedFragment).map(_ => "fragment" -> JsString(Mask))
      )
    }

  /** For parameters without a value, like `?token`, the name itself may be a token, so hide the
    * name too.
    */
  private def queryKeys(url: HttpUrl): Seq[String] =
    (0 until url.querySize).map { i =>
      if (url.queryParameterValue(i) == null) Mask else url.queryParameterName(i)
    }.distinct

  /** Converts to Java collections that can be passed to logstash-logback-encoder (Jackson 3) as-is.
    * If play-json types are passed, Jackson writes them out as plain objects.
    */
  def toJava(value: JsValue): AnyRef =
    value match {
      case JsObject(fields) =>
        val map = new java.util.LinkedHashMap[String, AnyRef]()
        fields.foreach((key, v) => map.put(key, toJava(v)))
        map
      case JsArray(items) =>
        val list = new java.util.ArrayList[AnyRef]()
        items.foreach(v => list.add(toJava(v)))
        list
      case JsString(s)  => s
      case JsNumber(n)  => n.bigDecimal
      case JsBoolean(b) => java.lang.Boolean.valueOf(b)
      case JsNull       => null
    }
}
