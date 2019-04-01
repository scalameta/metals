package tests.hover

import tests.pc.BaseHoverSuite

object HoverNamedArgSuite extends BaseHoverSuite {

  check(
    "named",
    """package a
      |object b {
      |  /**
      |   * Runs foo
      |   * @param named the argument
      |   */
      |  def foo(named: Int): Unit = ()
      |  <<foo(nam@@ed = 2)>>
      |}
      |""".stripMargin,
    """|```scala
       |def foo(named: Int): Unit
       |```
       |Runs foo
       |
       |**Parameters**
       |- `named`: the argument
       |""".stripMargin
  )

  check(
    "error",
    """package a
      |object c {
      |  def foo(a: String, named: Int): Unit = ()
      |  foo(nam@@ed = 2)
      |}
      |""".stripMargin,
    ""
  )

  check(
    "error2",
    """package a
      |object d {
      |  def foo(a: Int, named: Int): Unit = ()
      |  foo("error", nam@@ed = 2)
      |}
      |""".stripMargin,
    ""
  )

}
