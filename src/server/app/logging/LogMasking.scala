package logging

import okhttp3.HttpUrl
import play.api.libs.json._

/** ログに出す JSON の値を隠す。キー (どんな項目が送られてきたか) だけ残し、値は出さない。
  *
  * 元 URL のクエリには署名付き URL やパスワードリセットのトークンが入りうるので、隠すキーを列挙するのではなく 「出してよいキーだけ列挙する」側に倒している。項目が増えても既定で隠れる。
  *
  * URL の項目だけは、何が送られてきたか追えるよう部品に分けて出す ([[urlSummary]])。
  */
object LogMasking {

  val Mask = "***"

  /** パスはこの文字数で切る。URL は最大 2048 文字なので、丸ごと出すとログが読みにくい */
  val MaxPathLength = 256

  /** 値をすべて `Mask` に置き換える。`revealed` のキーは、値が文字列・数値・真偽値のときだけそのまま出す。`urls` のキーは、値が文字列のとき
    * [[urlSummary]] にする。
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

  /** URL をスキーム・ホスト・ポート (既定以外のとき)・パス・クエリのキーに分ける。
    *
    * トークンや署名はたいていクエリに入るので、クエリは値を隠してキーだけ出す (重複は 1 つにまとめる)。フラグメントも OAuth のトークンが入りうるので、あることだけ出す。
    * ユーザー情報 (user:pass@) は出さない。http / https として読めないものは丸ごと隠す。
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

  /** `?token` のように値の無いパラメータは、名前の側にトークンが入っていることがあるので名前も隠す。 */
  private def queryKeys(url: HttpUrl): Seq[String] =
    (0 until url.querySize).map { i =>
      if (url.queryParameterValue(i) == null) Mask else url.queryParameterName(i)
    }.distinct

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
