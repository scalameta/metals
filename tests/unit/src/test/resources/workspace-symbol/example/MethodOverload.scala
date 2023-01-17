package example

class MethodOverload/*example.MethodOverload#*/(b: String) {
  def this() = this("")
  def this(c: Int) = this("")
  val a/*example.MethodOverload#a.*/ = 2
  def a/*example.MethodOverload#a().*/(x: Int) = 2
  def a/*example.MethodOverload#a(+1).*/(x: Int, y: Int) = 2
}
