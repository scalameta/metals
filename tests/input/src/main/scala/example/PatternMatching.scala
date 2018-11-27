package example

class PatternMatching {
  val some = Some(1)
  some match {
    case Some(number) =>
      number
  }

  // tuple deconstruction
  val (left, right) = (1, 2)
  (left, right)

  // val deconstruction
  val Some(number1) =
    some
  println(number1)

  def localDeconstruction = {
    val Some(number2) =
      some
    number2
  }
}
