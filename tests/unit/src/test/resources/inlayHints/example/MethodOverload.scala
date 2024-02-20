package example

class MethodOverload(b: String) {
  def this() = this("")
  def this(c: Int) = this("")
  val a/*: Int<<scala/Int#>>*/ = 2
  def a(x: Int)/*: Int<<scala/Int#>>*/ = 2
  def a(x: Int, y: Int)/*: Int<<scala/Int#>>*/ = 2
}