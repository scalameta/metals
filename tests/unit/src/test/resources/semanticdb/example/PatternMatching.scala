package example

class PatternMatching/*example.PatternMatching#*/ {
  val some/*example.PatternMatching#some.*/ = Some/*scala.Some.*/(1)
  some/*example.PatternMatching#some.*/ match {
    case Some/*scala.Some.*/(number/*local0*/) =>
      number/*local0*/
  }

  // tuple deconstruction
  val (left/*local1*/, right/*local2*/) = (1, 2)
  (left/*example.PatternMatching#left.*/, right/*example.PatternMatching#right.*/)

  // val deconstruction
  val Some/*scala.Some.*/(number1/*local3*/) =
    some/*example.PatternMatching#some.*/
  println/*scala.Predef.println(+1).*/(number1/*example.PatternMatching#number1.*/)

  def localDeconstruction/*example.PatternMatching#localDeconstruction().*/ = {
    val Some/*scala.Some.*/(number2/*local5*/) =
      some/*example.PatternMatching#some.*/
    number2/*local4*/
  }
}
