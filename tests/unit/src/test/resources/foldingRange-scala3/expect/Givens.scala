trait Foo[A]>>region>>:
  def foo: String<<region<<

given Foo[String]>>region>> with 
  def a: String =>>region>>
    "f" +
      "o" +
      "o"<<region<<<<region<<

given Foo[String]>>region>> with 
  def a: String =>>region>>
    "f" +
      "o" +
      "o"<<region<<<<region<<

given x: AnyRef>>region>> with
  extension (y: String)>>region>>
    def a (y: Int): String =
      "f" +
        "o" +
        "o"<<region<<<<region<<

given stringV: String =>>region>>
  val a = "a"
  val b = "b"
  val c = "c"
  val d = "d"
  a + b + c + d<<region<<
