package example

class MethodOverload/*example.MethodOverload#*/(b/*example.MethodOverload#b.*/: String/*scala.Predef.String#*/) {
  def this/*example.MethodOverload#`<init>`(+1).*/() = this("")
  def this/*example.MethodOverload#`<init>`(+2).*/(c/*example.MethodOverload#`<init>`(+2).(c)*/: Int/*scala.Int#*/) = this("")
  val a/*example.MethodOverload#a.*/ = 2
  def a/*example.MethodOverload#a().*/(x/*example.MethodOverload#a().(x)*/: Int/*scala.Int#*/) = 2
  def a/*example.MethodOverload#a(+1).*/(x/*example.MethodOverload#a(+1).(x)*/: Int/*scala.Int#*/, y/*example.MethodOverload#a(+1).(y)*/: Int/*scala.Int#*/) = 2
}
