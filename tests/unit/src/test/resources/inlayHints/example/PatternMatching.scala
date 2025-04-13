package example

class PatternMatching {
  val some/*: Some<<scala/Some#>>[Int<<scala/Int#>>]*/ = Some/*[Int<<scala/Int#>>]*/(/*value = */1)
  some match {
    case Some(number/*: Int<<scala/Int#>>*/) =>
      number
  }

  // tuple deconstruction
  val (left/*: Int<<scala/Int#>>*/, right/*: Int<<scala/Int#>>*/) = (1, 2)
  (left, right)

  // val deconstruction
  val Some(number1/*: Int<<scala/Int#>>*/) =
    some
  println(/*x = */number1)

  def localDeconstruction/*: Int<<scala/Int#>>*/ = {
    val Some(number2/*: Int<<scala/Int#>>*/) =
      some
    number2
  }
}