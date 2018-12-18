/*example:9*/package example

/*MethodOverload:9*/class MethodOverload(b: String) {
  def this() = this("")
  def this(c: Int) = this("")
  /*a:6*/val a = 2
  /*a:7*/def a(x: Int) = 2
  /*a:8*/def a(x: Int, y: Int) = 2
}
