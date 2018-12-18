/*example:8*/package example

/*MethodOverload:8*/class MethodOverload(b: String) {
  def this() = this("")
  def this(c: Int) = this("")
  /*a:5*/val a = 2
  /*a:6*/def a(x: Int) = 2
  /*a:7*/def a(x: Int, y: Int) = 2
}
