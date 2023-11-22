package example

class Miscellaneous {
  // backtick identifier
  val `a b`/*: Int<<scala/Int#>>*/ = 42

  // block with only wildcard value
  def apply(): Unit = {
    val _ = 42
  }
  // infix + inferred apply/implicits/tparams
  (List/*[Int<<scala/Int#>>]*/(1)
    .map/*[Int<<scala/Int#>>]*/(_ + 1)
    ++/*[Int<<scala/Int#>>]*/
      List/*[Int<<scala/Int#>>]*/(3))
}