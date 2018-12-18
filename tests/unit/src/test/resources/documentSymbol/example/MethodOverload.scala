/*example*/package example

/*MethodOverload*/class MethodOverload(b: String) {
  def this() = this("")
  def this(c: Int) = this("")
  /*a*/val a = 2
  /*a*/def a(x: Int) = 2
  /*a*/def a(x: Int, y: Int) = 2
}
