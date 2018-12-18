/*example:23*/package example

/*PatternMatching:23*/class PatternMatching {
  /*some:3*/val some = Some(1)
  some match {
    case Some(number) =>
      number
  }

  // tuple deconstruction
  /*left:10*//*right:10*/val (left, right) = (1, 2)
  (left, right)

  // val deconstruction
  /*number1:15*/val Some(number1) =
    some
  println(number1)

  /*localDeconstruction:22*/def localDeconstruction = {
    val Some(number2) =
      some
    number2
  }
}
