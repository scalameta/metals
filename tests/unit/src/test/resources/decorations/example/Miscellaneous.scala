package example

class Miscellaneous {
  // backtick identifier
  val `a b` = 42

  // block with only wildcard value
  def apply(): Unit = {
    val _ = 42
  }
  // infix + inferred apply/implicits/tparams
  (List/*[Int]*/(1)
    .map/*[Int]*/(_ + 1)
    ++/*[Int]*/
      List/*[Int]*/(3))
}