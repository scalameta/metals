package tests.pc

object TypeDefinitionSuite extends BaseTypeDefinitionSuite {

  check("val")(
    """
      |<<class TClass(i: Int)>>
      |
      |object Main {
      |  val ts@@t = new TClass(2)
      |}""".stripMargin
  )

  check("constructor")(
    """
      |<<class TClass(i: Int) {}>>
      |
      |object Main {
      | def tst(m: TClass): Unit = {}
      |
      |  tst(new T@@Class(2))
      |}""".stripMargin
  )

  check("method")(
    """
      |object Main {
      |  def tst(): Unit = {}
      |
      |  ts@@/*scala/Unit# Unit.scala*/t()
      |}""".stripMargin
  )

  check("named-parameter")(
    """
      |object Main {
      |  def tst(par: Int): Unit = {}
      |
      |  tst(p@@/*scala/Int# Int.scala*/ar = 1)
      |}""".stripMargin
  )

  check("list")(
    """
      |object Main {
      | List(1).hea/*scala/Int# Int.scala*/@@d
      |}
      |""".stripMargin
  )

  check("class")(
    """
      |object Main {
      | <<class F@@oo(val x: Int)>>
      |}
      |""".stripMargin
  )

  check("val-keyword")(
    """
      |object Main {
      | va@@l x = 42
      |}
      |""".stripMargin
  )

  check("literal")(
    """
      |object Main {
      | val x = 4@@2
      |}
      |""".stripMargin
  )

  check("if")(
    """
      |object Main {
      | for {
      |   x <- List(1)
      |   i/*scala/collection/generic/FilterMonadic# FilterMonadic.scala*/@@f x > 1 // I'm not sure what's the expected behavior
      | } println(x)
      |}
      |""".stripMargin
  )

  check("predef")(
    """
      |object Main {
      | "".stripS/*java/lang/String# String.java*/@@uffix("foo")
      |}
      |""".stripMargin
  )

  check("method-generic")(
    """
      |object Main {
      | def foo[<<T>>](param: T): T = para@@m
      |}
      |""".stripMargin
  )

}
