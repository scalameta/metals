package example

class PatternMatching/*example.PatternMatching#*/ {
  val some/*example.PatternMatching#some.*/ = Some(1)
  some match {
    case Some(number) =>
      number
  }

  // tuple deconstruction
  val (left/*example.PatternMatching#left.*/, right/*example.PatternMatching#right.*/) = (1, 2)
  (left, right)

  // val deconstruction
  val Some(number1/*example.PatternMatching#number1.*/) =
    some
  println(number1)

  def localDeconstruction/*example.PatternMatching#localDeconstruction().*/ = {
    val Some(number2) =
      some
    number2
  }
}
