/*example(Package):24*/package example

/*PatternMatching(Class):24*/class PatternMatching {
  /*some(Constant):4*/val some = Some(1)
  some match {
    case Some(number) =>
      number
  }

  // tuple deconstruction
  /*left(Constant):11*//*right(Constant):11*/val (left, right) = (1, 2)
  (left, right)

  // val deconstruction
  /*number1(Constant):16*/val Some(number1) =
    some
  println(number1)

  /*localDeconstruction(Method):23*/def localDeconstruction = {
    val Some(number2) =
      some
    number2
  }
}
