package tests.hover

import tests.pc.BaseHoverSuite

class HoverErrorSuite extends BaseHoverSuite {
  override def requiresJdkSources: Boolean = true

  check(
    "no-type",
    """|object Main extends App{
       |  def hello(<<a@@aa>>) : Int = ""
       |}
       |""".stripMargin,
    ""
  )

  check(
    "error",
    """|final case class Dependency(
       |    org: String,
       |    name: Option[String],
       |    version: Option[String]
       |)
       |
       |object Dependency {
       |  def <<ap@@ply>>(org: String) = Dependency(org, None, None)
       |}
       |""".stripMargin,
    "".stripMargin
  )

}
