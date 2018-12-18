/*example:24*/package example

/*PatternMatching:24*/class PatternMatching {
  /*some:4*/val some = Some(1)
  some match {
    case Some(number) =>
      number
  }

  // tuple deconstruction
  /*left:11*//*right:11*/val (left, right) = (1, 2)
  (left, right)

  // val deconstruction
  /*number1:16*/val Some(number1) =
    some
  println(number1)

  /*localDeconstruction:23*/def localDeconstruction = {
    val Some(number2) =
      some
    number2
  }
}
