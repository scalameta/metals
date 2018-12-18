/*example:11*/package example

/*Miscellaneous:11*/class Miscellaneous {
  // backtick identifier
  /*a b:4*/val `a b` = 42

  // infix + inferred apply/implicits/tparams
  (List(1)
    .map(_ + 1)
    ++
      List(3))
}
