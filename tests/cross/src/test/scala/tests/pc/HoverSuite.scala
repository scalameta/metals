package tests.pc

import scala.meta.internal.metals.CompilerOffsetParams
import scala.collection.JavaConverters._
import tests.BasePCSuite

object HoverSuite extends BasePCSuite {

  def check(name: String, original: String, expected: String): Unit = {
    test(name) {
      val filename = "hoverForDebuggingPurposes.scala"
      val (code, offset) = params(original, filename)
      val hover = pc.hoverForDebuggingPurposes(
        CompilerOffsetParams(filename, code, offset)
      )
      val obtained = Option(hover) match {
        case Some(value) =>
          if (value.getContents.isRight) {
            value.getContents.getRight.getValue
          } else {
            value.getContents.getLeft.asScala
              .map { e =>
                if (e.isLeft) e.getLeft
                else {
                  s"""```${e.getRight.getLanguage}
                     |${e.getRight.getValue}
                     |```""".stripMargin
                }
              }
              .mkString("\n")
          }
        case None =>
          "<empty>"
      }
      assertNoDiff(obtained, expected)
    }
  }

  check(
    "basic",
    """object a {
      |  val x@@ = List(1)
      |}
      |""".stripMargin,
    """```scala
      |List[Int]
      |```
      |""".stripMargin
  )

  check(
    "named",
    """package a
      |object b {
      |  /**
      |   * Runs foo
      |   * @param named the argument
      |   */
      |  def foo(named: Int): Unit = ()
      |  foo(nam@@ed = 2)
      |}
      |""".stripMargin,
    """|```scala
       |named: Int
       |```
       |the argument
       |""".stripMargin
  )

}
