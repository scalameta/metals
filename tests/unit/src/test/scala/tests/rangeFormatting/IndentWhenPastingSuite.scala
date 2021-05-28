package tests.rangeFormatting

import scala.meta.internal.metals.BuildInfo

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.FormattingOptions
import tests.BaseLspSuite

class IndentWhenPastingSuite
    extends BaseLspSuite("IndentOnPasteRangeFormatting") {

  protected val scalaVersion: String = BuildInfo.scala3
  val formattingOptions: FormattingOptions = new FormattingOptions(
    /** tabSize: */
    2,
    /** insertSpaces */
    true
  )

  val blank = " "

  check(
    "correct-indentation",
    s"""
       |object Main:
       |  @@
       |end Main""".stripMargin,
    s"""
       |enum Color(val rgb: Int):
       |  case Red   extends Color(0xFF0000)
       |  case Green extends Color(0x00FF00)
       |  case Blue  extends Color(0x0000FF)
       |""".stripMargin,
    s"""
       |object Main:
       |  enum Color(val rgb: Int):
       |    case Red   extends Color(0xFF0000)
       |    case Green extends Color(0x00FF00)
       |    case Blue  extends Color(0x0000FF)
       |${blank * 2}
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  def check(
      name: TestOptions,
      testCase: String,
      paste: String,
      expectedCase: String,
      scalaVersion: String = scalaVersion,
      formattingOptions: FormattingOptions
  )(implicit loc: Location): Unit = {

    val base = testCase.replaceAll("(@@)", "")
    val expected = expectedCase
    test(name) {
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{"a":{"scalaVersion" : "$scalaVersion"}}
             |/a/src/main/scala/a/Main.scala
      """.stripMargin + base
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.rangeFormatting(
          "a/src/main/scala/a/Main.scala",
          testCase, // bez @@
          expected,
          paste,
          workspace,
          Some(formattingOptions)
        )
      } yield ()
    }
  }
}
