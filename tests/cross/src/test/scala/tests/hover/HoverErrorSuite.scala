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
}
