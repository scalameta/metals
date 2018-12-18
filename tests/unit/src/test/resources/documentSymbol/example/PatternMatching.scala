/*example*/package example

/*PatternMatching*/class PatternMatching {
  /*some*/val some = Some(1)
  some match {
    case Some(number) =>
      number
  }

  // tuple deconstruction
  /*left*//*right*/val (left, right) = (1, 2)
  (left, right)

  // val deconstruction
  /*number1*/val Some(number1) =
    some
  println(number1)

  /*localDeconstruction*/def localDeconstruction = {
    val Some(number2) =
      some
    number2
  }
}
