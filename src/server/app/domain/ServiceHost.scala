package domain

/** Host name of this service. Used to reject self-referencing links (redirect loops). */
final case class ServiceHost(value: String) {
  def matches(host: String): Boolean = host.equalsIgnoreCase(value)
}
