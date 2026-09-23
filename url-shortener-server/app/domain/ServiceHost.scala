package domain

/** 自サービスのホスト名。自己参照リンク（リダイレクトループ）を拒否するために使う。 */
final case class ServiceHost(value: String) {
  def matches(host: String): Boolean = host.equalsIgnoreCase(value)
}
