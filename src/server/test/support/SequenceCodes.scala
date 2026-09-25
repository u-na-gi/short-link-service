package support

/** Code generator stub that returns the given codes in order and counts calls. When they run out,
  * it keeps returning the last one.
  */
final class SequenceCodes(codes: String*) extends (() => String) {
  var calls = 0

  override def apply(): String = {
    val code = codes(math.min(calls, codes.length - 1))
    calls += 1
    code
  }
}
