package example

class Miscellaneous {
  // backtick identifier
  val `a b`/*: Int<<scala/Int#>>*/ = 42

  // block with only wildcard value
  def apply(): Unit = {
    val _ = 42
  }
  // infix + inferred apply/implicits/tparams
  (List/*[Int<<scala/Int#>>]*/(/*elems = */1)
    .map/*[Int<<scala/Int#>>]*/(/*f = */_ + 1)/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/
    ++/*         : List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/
      List/*[Int<<scala/Int#>>]*/(/*elems = */3))
}