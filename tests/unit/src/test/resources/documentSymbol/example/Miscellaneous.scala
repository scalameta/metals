/*example*/package example

/*Miscellaneous*/class Miscellaneous {
  // backtick identifier
  /*a b*/val `a b` = 42

  // infix + inferred apply/implicits/tparams
  (List(1)
    .map(_ + 1)
    ++
      List(3))
}
