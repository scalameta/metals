package example

class PatternMatching {
  val some/*: Some<<scala/Some#>>[Int<<scala/Int#>>]*/ = Some/*[Int<<scala/Int#>>]*/(1)
  some match {
    case Some/*[Int<<scala/Int#>>]*/(number/*: Int<<scala/Int#>>*/) =>
      number
  }

  // tuple deconstruction
  val (left/*: Int<<scala/Int#>>*/, right/*: Int<<scala/Int#>>*/) = (1, 2)
  (left, right)

  // val deconstruction
  val Some/*[Int<<scala/Int#>>]*/(number1/*: Int<<scala/Int#>>*/) =
    some
  println(number1)

  def localDeconstruction/*: Int<<scala/Int#>>*/ = {
    val Some/*[Int<<scala/Int#>>]*/(number2/*: Int<<scala/Int#>>*/) =
      some
    number2
  }
}