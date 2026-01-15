package tests.rangeFormatting

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.UserConfiguration

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
    true,
  )
  val tabsFormattingOptions: FormattingOptions = new FormattingOptions(
    /** tabSize: */
    1,
    /** insertSpaces */
    false,
  )

  val blank = " "

  override def userConfig: UserConfiguration =
    super.userConfig.copy(enableIndentOnPaste = true)

  check(
    "single-line",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    """val a: String = "a"""",
    """
      |object Main:
      |  val a: String = "a"
      |end Main
      |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "single-code-line",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    "val a: String = \"a\"",
    """
      |object Main:
      |  val a: String = "a"
      |end Main""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "correct-indentation",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    """|enum Color(val rgb: Int):
       |  case Red   extends Color(0xFF0000)
       |  case Green extends Color(0x00FF00)
       |  case Blue  extends Color(0x0000FF)""".stripMargin,
    """
      |object Main:
      |  enum Color(val rgb: Int):
      |    case Red   extends Color(0xFF0000)
      |    case Green extends Color(0x00FF00)
      |    case Blue  extends Color(0x0000FF)
      |end Main""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "correct-indentation-multi-level",
    """
      |object Main:
      |  object SubMain:
      |    @@
      |  end SubMain
      |end Main""".stripMargin,
    """|enum Color(val rgb: Int):
       |  case Red   extends Color(0xFF0000)
       |  case Green extends Color(0x00FF00)
       |  case Blue  extends Color(0x0000FF)""".stripMargin,
    """
      |object Main:
      |  object SubMain:
      |    enum Color(val rgb: Int):
      |      case Red   extends Color(0xFF0000)
      |      case Green extends Color(0x00FF00)
      |      case Blue  extends Color(0x0000FF)
      |  end SubMain
      |end Main""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "cursor-init-line-multi-level",
    """
      |object Main:
      |  object SubMain:
      |    @@
      |  end SubMain
      |end Main""".stripMargin,
    """|enum Color(val rgb: Int):
       |  case Red   extends Color(0xFF0000)
       |  case Green extends Color(0x00FF00)
       |  case Blue  extends Color(0x0000FF)""".stripMargin,
    """
      |object Main:
      |  object SubMain:
      |    enum Color(val rgb: Int):
      |      case Red   extends Color(0xFF0000)
      |      case Green extends Color(0x00FF00)
      |      case Blue  extends Color(0x0000FF)
      |  end SubMain
      |end Main""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "cursor-init-line-multi-level-spaced",
    """
      |object Main:
      |  object SubMain:
      |    @@
      |  end SubMain
      |end Main""".stripMargin,
    """|                        enum Color(val rgb: Int):
       |                          case Red   extends Color(0xFF0000)
       |                          case Green extends Color(0x00FF00)
       |                          case Blue  extends Color(0x0000FF)""".stripMargin,
    """
      |object Main:
      |  object SubMain:
      |    enum Color(val rgb: Int):
      |      case Red   extends Color(0xFF0000)
      |      case Green extends Color(0x00FF00)
      |      case Blue  extends Color(0x0000FF)
      |  end SubMain
      |end Main""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "line-continuation",
    """
      |object Main:
      |  object SubMain:
      |    enum @@
      |  end SubMain
      |end Main""".stripMargin,
    """|                       Color(val rgb: Int):
       |                          case Red   extends Color(0xFF0000)
       |                          case Green extends Color(0x00FF00)
       |                          case Blue  extends Color(0x0000FF)""".stripMargin,
    """|object Main:
       |  object SubMain:
       |    enum Color(val rgb: Int):
       |      case Red extends Color(0xff0000)
       |      case Green extends Color(0x00ff00)
       |      case Blue extends Color(0x0000ff)
       |  end SubMain
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "multistring-no-code-deletion",
    """
      |object Main:
      |  object SubMain:
      |    val a: String = \"\"\"| multiline
      |                     | string
      |                     |
      |    @@
      |  end SubMain
      |end Main""".stripMargin,
    """|                     |
       |                     |\"\"\".stripMargin
       |
       |  val b: String = \"\"\"| multiline
       |                    | string
       |                    |\"\"\".stripMargin""".stripMargin,
    """|object Main:
       |  object SubMain:
       |    val a: String = \"\"\"| multiline
       |                     | string
       |                     |
       |    |
       |                       |\"\"\".stripMargin
       |
       |    val b: String = \"\"\"| multiline
       |                      | string
       |                      |\"\"\".stripMargin
       |  end SubMain
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "correct-indentation-after-multiline-string",
    """
      |object Main:
      |  object SubMain:
      |    val a: String = \"\"\"| multiline
      |                     | string
      |                     |\"\"\".stripMargin
      |    @@
      |  end SubMain
      |end Main""".stripMargin,
    """|  val b: String = "b"
       |  val c: String = "c"""".stripMargin,
    """
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
    formattingOptions,
  )

  check(
    "paste-after-correctly-indented-line",
    """
      |object Main:
      |  val a: String = "a"
      |  @@
      |end Main""".stripMargin,
    """enum Color(val rgb: Int):
      |  case Red   extends Color(0xFF0000)
      |  case Green extends Color(0x00FF00)
      |  case Blue  extends Color(0x0000FF)""".stripMargin,
    """
      |object Main:
      |  val a: String = "a"
      |  enum Color(val rgb: Int):
      |    case Red   extends Color(0xFF0000)
      |    case Green extends Color(0x00FF00)
      |    case Blue  extends Color(0x0000FF)
      |end Main""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "extension",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    """|extension [T](using b: B)(s: String)(using A)
       |  def double = s * 2
       |""".stripMargin,
    """|object Main:
       |  extension [T](using b: B)(s: String)(using A)
       |    def double = s * 2
       |
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "keep-empty-lines-inside-paste",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    """|trait X[A] {
       |    def doX: A
       |}
       |
       |
       |def foo: X[A] = ???
       |
       |
       |def bar: X[A] = ???
       |""".stripMargin,
    """|object Main:
       |  trait X[A] {
       |      def doX: A
       |  }
       |
       |
       |  def foo: X[A] = ???
       |
       |
       |  def bar: X[A] = ???
       |
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "keep-empty-lines-inside-paste2",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    """|trait X[A] {
       |
       |  def doX: A
       |
       |}""".stripMargin,
    """|object Main:
       |  trait X[A] {
       |
       |    def doX: A
       |
       |  }
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "if-paren",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    """|if (cond)
       |  def double = s * 2
       |  double""".stripMargin,
    """|object Main:
       |  if (cond)
       |    def double = s * 2
       |    double
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "paste-without-leading-nl",
    """
      |object Main {
      |  @@
      |}""".stripMargin,
    """trait X[A] {
      |
      |    def foo: X[A]
      |}
      |""".stripMargin,
    """|object Main {
       |  trait X[A] {
       |
       |      def foo: X[A]
       |  }
       |
       |}
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "normalize-tabs-to-spaces",
    """
      |object Main:
      |  @@
      |end Main""".stripMargin,
    s"""|\tif (cond)
        |\t\tdef double = s * 2
        |\t\tdouble""".stripMargin,
    """|object Main:
       |  if (cond)
       |    def double = s * 2
       |    double
       |end Main
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "normalize-spaces-to-tabs",
    s"""
       |object Main:
       |\t@@
       |end Main""".stripMargin,
    """|if (cond)
       |  def double = s * 2
       |  double""".stripMargin,
    s"""|object Main:
        |\tif (cond)
        |\t\tdef double = s * 2
        |\t\tdouble
        |end Main
        |""".stripMargin,
    scalaVersion,
    tabsFormattingOptions,
  )

  check(
    "breaking-code",
    """
      |object Main:
      |  @@val outFile = ""
      |end Main""".stripMargin,
    "outFile",
    """
      |object Main:
      |  outFileval outFile = ""
      |end Main""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "issue-3108",
    """
      |object Main{
      |  private def outFile(
      |    abc@@: String
      |  ) = ""
      |}""".stripMargin,
    "abc",
    """
      |object Main{
      |  private def outFile(
      |      abcabc: String
      |  ) = ""
      |}""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "paren-indent",
    """
      |object Main{
      |  private def outFile(
      |    @@
      |  ) = ""
      |}""".stripMargin,
    "abc: String",
    """
      |object Main{
      |  private def outFile(
      |    abc: String
      |  ) = ""
      |}""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "paren-bracket",
    """
      |object Main{
      |  private def outFile[
      |    @@
      |  ] = ""
      |}""".stripMargin,
    "String",
    """
      |object Main{
      |  private def outFile[
      |    String
      |  ] = ""
      |}""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "methods",
    """
      |object Main {
      |  def hello() = 
      |    println("hello")
      |    println("hello")
      |    @@ 
      |}""".stripMargin,
    """|def bye() =
       |  println("bye")
       |  println("bye") """.stripMargin,
    """|object Main {
       |  def hello() = 
       |    println("hello")
       |    println("hello")
       |    def bye() =
       |      println("bye")
       |      println("bye")  
       |}
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "methods-same-line",
    """
      |object Main {
      |  def hello() = 
      |    println("hello")
      |    println("hello")
      |  @@ 
      |}""".stripMargin,
    """|
       |def bye() =
       |  println("bye")
       |
       |  println("bye")
       |""".stripMargin,
    """|object Main {
       |  def hello() = 
       |    println("hello")
       |    println("hello")
       |
       |  def bye() =
       |    println("bye")
       |
       |    println("bye")
       |
       |}
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  check(
    "pasting-after-exiting-code",
    s"""
       |object Main {
       |  ""@@
       |}""".stripMargin,
    s"""|.stripMargin
        |  123""".stripMargin,
    """|object Main {
       |  "".stripMargin
       |  123
       |}
       |""".stripMargin,
    scalaVersion,
    formattingOptions,
  )

  def check(
      name: TestOptions,
      testCase: String,
      paste: String,
      expectedCase: String,
      scalaVersion: String = scalaVersion,
      formattingOptions: FormattingOptions,
  )(implicit loc: Location): Unit = {

    val base = testCase.replaceAll("(@@)", "")
    val expected = expectedCase
    test(name) {
      for {
        _ <- initialize(
          s"""/metals.json
             |{"a":{"scalaVersion" : "$scalaVersion"}}
             |/a/src/main/scala/a/Main.scala
      """.stripMargin + base
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.rangeFormatting(
          "a/src/main/scala/a/Main.scala",
          testCase, // without @@
          expected,
          paste,
          workspace,
          Some(formattingOptions),
        )
      } yield ()
    }
  }
}
