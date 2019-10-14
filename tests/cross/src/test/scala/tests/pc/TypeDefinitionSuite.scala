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
      |  def tst(<<par>>: Int): Unit = {}
      |
      |  tst(p@@ar = 1)
      |}""".stripMargin
  )

}
