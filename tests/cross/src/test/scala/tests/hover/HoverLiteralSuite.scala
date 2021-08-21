package tests.hover

import tests.pc.BaseHoverSuite

class HoverLiteralSuite extends BaseHoverSuite {

  check(
    "literal-int",
    """object a {
      |  val x = 4@@2
      |}
      |""".stripMargin,
    """|```scala
       |Int
       |```""".stripMargin.hover
  )

  check(
    "literal-double",
    """object a {
      |  val x = 4@@2d
      |}
      |""".stripMargin,
    """|```scala
       |Double
       |```""".stripMargin.hover
  )

  check(
    "literal-float",
    """object a {
      |  val x = 4@@2f
      |}
      |""".stripMargin,
    """|```scala
       |Float
       |```""".stripMargin.hover
  )

  check(
    "literal-long",
    """object a {
      |  val x = 4@@2L
      |}
      |""".stripMargin,
    """|```scala
       |Long
       |```""".stripMargin.hover
  )

  check(
    "literal-string",
    """object a {
      |  val x = "Hel@@lo"
      |}
      |""".stripMargin,
    """|```scala
       |String
       |```""".stripMargin.hover
  )

  check(
    "interpolator-part",
    """object a {
      |  val name = "John"
      |  s"Hel@@lo $name"
      |}
      |""".stripMargin,
    """|```scala
       |String
       |```""".stripMargin
  )

}
