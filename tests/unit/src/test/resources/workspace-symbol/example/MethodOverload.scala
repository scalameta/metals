package example

class MethodOverload/*example.MethodOverload#*/(b: String) {
  def this() = this("")
  def this(c: Int) = this("")
  val a = 2
  def a(x: Int) = 2
  def a(x: Int, y: Int) = 2
}
