trait Foo[A]:
  def foo: String

given Foo[String]>>region>> with 
  def a: String =
    "f" +
      "o" +
      "o"<<region<<

given Foo[String]>>region>> with 
  def a: String = 
    "f" +
      "o" +
      "o"<<region<<

given x: AnyRef>>region>> with
  extension (y: String)
    def a (y: Int): String =
      "f" +
        "o" +
        "o"<<region<<

given stringV: String =>>region>>
  val a = "a"
  val b = "b"
  val c = "c"
  val d = "d"
  a + b + c + d<<region<<
