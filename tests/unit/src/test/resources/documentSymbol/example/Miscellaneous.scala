/*example:12*/package example

/*Miscellaneous:12*/class Miscellaneous {
  // backtick identifier
  /*a b:5*/val `a b` = 42

  // infix + inferred apply/implicits/tparams
  (List(1)
    .map(_ + 1)
    ++
      List(3))
}
