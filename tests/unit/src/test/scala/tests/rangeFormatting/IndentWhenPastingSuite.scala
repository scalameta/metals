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
    "single-line",
    s"""
       |object Main:
       |  @@
       |end Main""".stripMargin,
    s"""val a: String = "a"
       |""".stripMargin,
    s"""
       |object Main:
       |  val a: String = "a"
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "single-code-line",
    s"""
       |object Main:
       |  @@
       |end Main""".stripMargin,
    s"""
       |val a: String = "a"
       |""".stripMargin,
    s"""
       |object Main:
       |  val a: String = "a"
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

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
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "correct-indentation-multi-level",
    s"""
       |object Main:
       |  object SubMain:
       |    @@
       |  end SubMain
       |end Main""".stripMargin,
    s"""
       |enum Color(val rgb: Int):
       |  case Red   extends Color(0xFF0000)
       |  case Green extends Color(0x00FF00)
       |  case Blue  extends Color(0x0000FF)
       |""".stripMargin,
    s"""
       |object Main:
       |  object SubMain:
       |    enum Color(val rgb: Int):
       |      case Red   extends Color(0xFF0000)
       |      case Green extends Color(0x00FF00)
       |      case Blue  extends Color(0x0000FF)
       |  end SubMain
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "cursor-init-line-multi-level",
    s"""
       |object Main:
       |  object SubMain:
       |@@
       |  end SubMain
       |end Main""".stripMargin,
    s"""
       |enum Color(val rgb: Int):
       |  case Red   extends Color(0xFF0000)
       |  case Green extends Color(0x00FF00)
       |  case Blue  extends Color(0x0000FF)
       |""".stripMargin,
    s"""
       |object Main:
       |  object SubMain:
       |    enum Color(val rgb: Int):
       |      case Red   extends Color(0xFF0000)
       |      case Green extends Color(0x00FF00)
       |      case Blue  extends Color(0x0000FF)
       |  end SubMain
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "cursor-init-line-multi-level-spaced",
    s"""
       |object Main:
       |  object SubMain:
       |@@
       |  end SubMain
       |end Main""".stripMargin,
    s"""
       |                        enum Color(val rgb: Int):
       |                          case Red   extends Color(0xFF0000)
       |                          case Green extends Color(0x00FF00)
       |                          case Blue  extends Color(0x0000FF)
       |""".stripMargin,
    s"""
       |object Main:
       |  object SubMain:
       |    enum Color(val rgb: Int):
       |      case Red   extends Color(0xFF0000)
       |      case Green extends Color(0x00FF00)
       |      case Blue  extends Color(0x0000FF)
       |  end SubMain
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "line-continuation",
    s"""
       |object Main:
       |  object SubMain:
       |    enum @@
       |  end SubMain
       |end Main""".stripMargin,
    s"""                       Color(val rgb: Int):
       |                          case Red   extends Color(0xFF0000)
       |                          case Green extends Color(0x00FF00)
       |                          case Blue  extends Color(0x0000FF)
       |""".stripMargin,
    s"""
       |object Main:
       |  object SubMain:
       |    enum                        Color(val rgb: Int):
       |      case Red   extends Color(0xFF0000)
       |      case Green extends Color(0x00FF00)
       |      case Blue  extends Color(0x0000FF)
       |  end SubMain
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "multistring-no-code-deletion",
    s"""
       |object Main:
       |  object SubMain:
       |    val a: String = \"\"\"| multiline
       |                     | string
       |                     |
       |    @@
       |  end SubMain
       |end Main""".stripMargin,
    s"""
       |                     |
       |                     |\"\"\".stripMargin
       |
       |  val b: String = \"\"\"| multiline
       |                    | string
       |                    |\"\"\".stripMargin
       |""".stripMargin,
    s"""
       |object Main:
       |  object SubMain:
       |    val a: String = \"\"\"| multiline
       |                     | string
       |                     |
       |    |
       |    |\"\"\".stripMargin
       |    val b: String = \"\"\"| multiline
       |   | string
       |   |\"\"\".stripMargin
       |  end SubMain
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "correct-indentation-after-multiline-string",
    s"""
       |object Main:
       |  object SubMain:
       |    val a: String = \"\"\"| multiline
       |                     | string
       |                     |\"\"\".stripMargin
       |    @@
       |  end SubMain
       |end Main""".stripMargin,
    s"""
       |  val b: String = "b"
       |  val c: String = "c"
       |""".stripMargin,
    s"""
       |object Main:
       |  object SubMain:
       |    val a: String = \"\"\"| multiline
       |                     | string
       |                     |\"\"\".stripMargin
       |    val b: String = "b"
       |    val c: String = "c"
       |  end SubMain
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "paste-after-correctly-indented-line",
    s"""
       |object Main:
       |  val a: String = "a"
       |@@
       |end Main""".stripMargin,
    s"""enum Color(val rgb: Int):
       |  case Red   extends Color(0xFF0000)
       |  case Green extends Color(0x00FF00)
       |  case Blue  extends Color(0x0000FF)
       |""".stripMargin,
    s"""
       |object Main:
       |  val a: String = "a"
       |  enum Color(val rgb: Int):
       |    case Red   extends Color(0xFF0000)
       |    case Green extends Color(0x00FF00)
       |    case Blue  extends Color(0x0000FF)
       |end Main""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "extension",
    s"""
       |object Main:
       |  @@
       |end Main""".stripMargin,
    s"""
       |extension [T](using b: B)(s: String)(using A)
       |  def double = s * 2
       |""".stripMargin,
    """|object Main:
       |  extension [T](using b: B)(s: String)(using A)
       |    def double = s * 2
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions
  )

  check(
    "if-paren",
    s"""
       |object Main:
       |  @@
       |end Main""".stripMargin,
    s"""
       |if (cond)
       |  def double = s * 2
       |  double
       |""".stripMargin,
    """|object Main:
       |  if (cond)
       |    def double = s * 2
       |    double
       |end Main
       |""".stripMargin,
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
