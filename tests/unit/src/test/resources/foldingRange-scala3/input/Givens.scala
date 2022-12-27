trait Foo[A]:
  def foo: String

given Foo[String] with 
  def a: String =
    "f" +
      "o" +
      "o"

given Foo[String] with 
  def a: String =
    "f" +
      "o" +
      "o"

given x: AnyRef with
  extension (y: String)
    def a (y: Int): String =
      "f" +
        "o" +
        "o"

given stringV: String =
  val a = "a"
  val b = "b"
  val c = "c"
  val d = "d"
  a + b + c + d
