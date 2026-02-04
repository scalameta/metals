package foo

class C(x: Int, y: String = "ok") {
  def f(a: Int = 1, b: String = "x"): String = b
}

object C {
  def g(a: Int, b: Long = 2L): Long = b
}
