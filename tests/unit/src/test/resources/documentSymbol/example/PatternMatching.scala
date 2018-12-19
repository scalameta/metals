/*example(Package):24*/package example

/*example.PatternMatching(Class):24*/class PatternMatching {
  /*example.PatternMatching#some(Constant):4*/val some = Some(1)
  some match {
    case Some(number) =>
      number
  }

  // tuple deconstruction
  /*example.PatternMatching#left(Constant):11*//*example.PatternMatching#right(Constant):11*/val (left, right) = (1, 2)
  (left, right)

  // val deconstruction
  /*example.PatternMatching#number1(Constant):16*/val Some(number1) =
    some
  println(number1)

  /*example.PatternMatching#localDeconstruction(Method):23*/def localDeconstruction = {
    val Some(number2) =
      some
    number2
  }
}
