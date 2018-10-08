package example

class Miscellaneous/*Miscellaneous.semanticdb*/ {
  // backtick identifier
  val `a b`/*Miscellaneous.semanticdb*/ = 42

  // infix + inferred apply/implicits/tparams
  (List/*List.scala*/(1)
    .map/*List.scala*/(_ +/*Int.scala*/ 1)
    ++/*List.scala*/
      List/*List.scala*/(3))
}
