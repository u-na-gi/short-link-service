package logging

import play.api.libs.json._

/** ログに出す JSON の値を隠す。キー (どんな項目が送られてきたか) だけ残し、値は出さない。
  *
  * 元 URL のクエリには署名付き URL やパスワードリセットのトークンが入りうるので、隠すキーを列挙するのではなく 「出してよいキーだけ列挙する」側に倒している。項目が増えても既定で隠れる。
  */
object LogMasking {

  val Mask = "***"

  /** 値をすべて `Mask` に置き換える。`revealed` のキーは、値が文字列・数値・真偽値のときだけそのまま出す。 */
  def mask(value: JsValue, revealed: Set[String] = Set.empty): JsValue =
    value match {
      case JsObject(fields) =>
        JsObject(fields.map {
          case (key, v @ (_: JsString | _: JsNumber | _: JsBoolean)) if revealed(key) => key -> v
          case (key, v)                                                               =>
            key -> mask(v, revealed)
        })
      case JsArray(items) => JsArray(items.map(mask(_, revealed)))
      case _              => JsString(Mask)
    }

  /** logstash-logback-encoder (Jackson 3) にそのまま渡せる Java のコレクションにする。 play-json の型を渡すと Jackson
    * がただのオブジェクトとして書き出してしまうため。
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
