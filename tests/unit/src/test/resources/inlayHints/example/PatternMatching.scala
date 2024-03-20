package example

class PatternMatching {
  val some/*: Some<<scala/Some#>>[Int<<scala/Int#>>]*/ = Some/*[Int<<scala/Int#>>]*/(1)
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

  def localDeconstruction/*: Int<<scala/Int#>>*/ = {
    val Some(number2) =
      some
    number2
  }
}