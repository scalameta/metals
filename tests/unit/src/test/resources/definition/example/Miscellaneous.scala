package example

class Miscellaneous/*Miscellaneous.scala*/ {
  // backtick identifier
  val `a b`/*Miscellaneous.scala*/ = 42

  // block with only wildcard value
  def apply/*Miscellaneous.scala*/(): Unit/*Unit.scala*/ = {
    val _ = 42
  }
  // infix + inferred apply/implicits/tparams
  (List/*package.scala*/(1)
    .map/*List.scala*/(_ +/*Int.scala*/ 1)
    ++/*Iterable.scala*/
      List/*package.scala*/(3))
}
