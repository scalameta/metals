/*example(Package):9*/package example

/*MethodOverload(Class):9*/class MethodOverload(b: String) {
  def this() = this("")
  def this(c: Int) = this("")
  /*a(Constant):6*/val a = 2
  /*a(Method):7*/def a(x: Int) = 2
  /*a(Method):8*/def a(x: Int, y: Int) = 2
}
