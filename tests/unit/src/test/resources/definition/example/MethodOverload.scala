package example

class MethodOverload/*MethodOverload.semanticdb*/(b/*MethodOverload.semanticdb*/: String/*Predef.scala*/) {
  def this/*unexpected: example.MethodOverload#`<init>`(+1).*/() = this("")
  def this/*unexpected: example.MethodOverload#`<init>`(+2).*/(c/*MethodOverload.semanticdb*/: Int/*Int.scala*/) = this("")
  val a/*MethodOverload.semanticdb*/ = 2
  def a/*MethodOverload.semanticdb*/(x/*MethodOverload.semanticdb*/: Int/*Int.scala*/) = 2
  def a/*MethodOverload.semanticdb*/(x/*MethodOverload.semanticdb*/: Int/*Int.scala*/, y/*MethodOverload.semanticdb*/: Int/*Int.scala*/) = 2
}
