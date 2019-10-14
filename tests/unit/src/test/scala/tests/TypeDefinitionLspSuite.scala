package tests

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig

object TypeDefinitionLspSuite
    extends BaseTypeDefinitionLspSuite
    with TestHovers {
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(
      statistics = new StatisticsConfig("diagnostics")
    )

  check("int")(
    query = """|object Main {
               |  val ts/*.metals/readonly/scala/Int.scala*/@@t: Int = 2
               |}""".stripMargin
  )

  check("basic")(
    query = """
              |package a
              |
              |<<class Main(i: Int) {}>>
              |
              |object Main extends App {
              |  val te@@st = new Main(1)
              |}
        """.stripMargin
  )

  check("method")(
    query = """
              |package a
              |
              |<<class Main(i: Int) {}>>
              |
              |object Main extends App {
              |  def foo(mn: Main): Unit = {
              |     println(m@@n)
              |  }
              |}
        """.stripMargin
  )

  check("method-definition")(
    query = """
              |package a
              |class Main(i: Int) {}
              |
              |object Main extends App {
              |  def foo(mn: Main): Unit = {
              |     println(mn)
              |  }
              |
              |  fo/*.metals/readonly/scala/Unit.scala*/@@o(new Main(1))
              |}
        """.stripMargin
  )

  check("named-parameter")(
    query = """
              |case class CClass(str: String) {}
              |
              |object Main {
              |  def tst(par: CClass): Unit = {}
              |
              |  tst(p@@ar = CClass("dads"))
              |}""".stripMargin
  )

  check("pattern-match")(
    query = """
              |case class CClass(str: String) {}
              |
              |object Main {
              |  CClass("test") match {
              |    case CClass(st/*.metals/readonly/java/lang/String.java*/@@r) =>
              |       println(str)
              |    case _ =>
              |  }
              |}""".stripMargin
  )

}
