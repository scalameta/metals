package example

class MethodOverload/*MethodOverload.scala*/(b/*MethodOverload.scala*/: String/*Predef.scala*/) {
  def this/*unexpected: example.MethodOverload#`<init>`(+1).*/() = this("")
  def this/*unexpected: example.MethodOverload#`<init>`(+2).*/(c/*MethodOverload.scala*/: Int/*Int.scala*/) = this("")
  val a/*MethodOverload.scala*/ = 2
  def a/*MethodOverload.scala*/(x/*MethodOverload.scala*/: Int/*Int.scala*/) = 2
  def a/*MethodOverload.scala*/(x/*MethodOverload.scala*/: Int/*Int.scala*/, y/*MethodOverload.scala*/: Int/*Int.scala*/) = 2
}
