/*example(Package):16*/package example

/*example.Miscellaneous(Class):16*/class Miscellaneous {
  // backtick identifier
  /*example.Miscellaneous#a b(Constant):5*/val `a b` = 42

  // block with only wildcard value
  /*example.Miscellaneous#apply(Method):10*/def apply(): Unit = {
    val _ = 42
  }
  // infix + inferred apply/implicits/tparams
  (List(1)
    .map(_ + 1)
    ++
      List(3))
}
