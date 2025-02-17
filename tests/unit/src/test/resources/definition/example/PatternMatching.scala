package example

class PatternMatching/*PatternMatching.scala*/ {
  val some/*PatternMatching.scala*/ = Some/*Option.scala*/(1)
  some/*PatternMatching.scala*/ match {
    case Some/*Option.scala*/(number/*PatternMatching.semanticdb*/) =>
      number/*PatternMatching.semanticdb*/
  }

  // tuple deconstruction
  val (left/*PatternMatching.scala*/, right/*PatternMatching.scala*/) = (1, 2)
  (left/*PatternMatching.scala*/, right/*PatternMatching.scala*/)

  // val deconstruction
  val Some/*Option.scala*/(number1/*PatternMatching.scala*/) =
    some/*PatternMatching.scala*/
  println/*Predef.scala*/(number1/*PatternMatching.scala*/)

  def localDeconstruction/*PatternMatching.scala*/ = {
    val Some/*Option.scala*/(number2/*PatternMatching.semanticdb*/) =
      some/*PatternMatching.scala*/
    number2/*PatternMatching.semanticdb*/
  }
}
