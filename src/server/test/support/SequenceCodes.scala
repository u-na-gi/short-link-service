package support

/** 渡したコードを順に返し、呼ばれた回数を数える採番スタブ。使い切ったら最後のコードを返し続ける。 */
final class SequenceCodes(codes: String*) extends (() => String) {
  var calls = 0

  override def apply(): String = {
    val code = codes(math.min(calls, codes.length - 1))
    calls += 1
    code
  }
}
