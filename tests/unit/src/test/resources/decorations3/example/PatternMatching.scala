package example

class PatternMatching {
  val some/*: Some[Int]*/ = Some/*[Int]*/(1)
  some match {
    case Some/*[Int]*/(number/*: Int*/) =>
      number
  }

  // tuple deconstruction
  val (left/*: Int*/, right/*: Int*/) = (1, 2)
  (left, right)

  // val deconstruction
  val Some/*[Int]*/(number1/*: Int*/) =
    some
  println(number1)

  def localDeconstruction/*: Int*/ = {
    val Some/*[Int]*/(number2/*: Int*/) =
      some
    number2
  }
}