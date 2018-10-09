package example

class Miscellaneous/*Miscellaneous.scala*/ {
  // backtick identifier
  val `a b`/*Miscellaneous.scala*/ = 42

  // infix + inferred apply/implicits/tparams
  (List/*List.scala*/(1)
    .map/*List.scala*/(_ +/*Int.scala*/ 1)
    ++/*List.scala*/
      List/*List.scala*/(3))
}
