/*example(Package):12*/package example

/*example.Miscellaneous(Class):12*/class Miscellaneous {
  // backtick identifier
  /*example.Miscellaneous#a b(Constant):5*/val `a b` = 42

  // infix + inferred apply/implicits/tparams
  (List(1)
    .map(_ + 1)
    ++
      List(3))
}
