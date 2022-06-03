package tests.rangeFormatting

import scala.meta.internal.metals.UserConfiguration

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.FormattingOptions
import tests.BaseLspSuite

class MultilineStringRangeFormattingWhenSelectingSuite
    extends BaseLspSuite("rangeFormatting") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(enableIndentOnPaste = true)

  check(
    "start-misindent-line",
    s"""
       |object Main {
       |  val str = '''
       |              |first line
       |           <<        |second line
       |                  |third line
       |          |fourth line'''.stripMargin>>
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |              |first line
       |                   |second line
       |                   |third line
       |                   |fourth line'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "starting-well-indent-line",
    s"""
       |object Main {
       |  val str = '''
       |              |first line<<
       |                   |second line
       |                  |third line
       |          |fourth line'''.stripMargin>>
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |              |first line
       |              |second line
       |              |third line
       |              |fourth line'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "starting-fist-line",
    s"""
       |object Main {
       |  val str = '''first line<<
       |                   |second line
       |                  |third line
       |          |fourth line'''.stripMargin>>
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''first line
       |              |second line
       |              |third line
       |              |fourth line'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "first-line-with-pipe",
    s"""
       |object Main {
       |  val str = '''|first line<<
       |                   |second line
       |                  |third line
       |          |fourth line'''.stripMargin>>
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|first line
       |               |second line
       |               |third line
       |               |fourth line'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "entire-string",
    s"""
       |object Main {
       |  <<val str = '''|first line
       |                   |second line
       |                  |third line
       |          |fourth line'''.stripMargin>>
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|first line
       |               |second line
       |               |third line
       |               |fourth line'''.stripMargin
       |}""".stripMargin,
  )

  val blank = ""

  // This test shows that currently if current selection contains more than one and only one multi-line string
  // we use the IndentOnPaste onRangeFormatter
  check(
    "two-string",
    s"""
       |object Main {
       |<<  val firstString = '''
       |                      |first line
       |                          |second line'''.stripMargin
       |
       |val str2 = '''
       |             |first line
       |             |second line'''.stripMargin>>
       |}""".stripMargin,
    """|object Main {
       |val firstString = '''
       |                      |first line
       |                          |second line'''.stripMargin
       |
       |val str2 = '''
       |             |first line
       |             |second line'''.stripMargin
       |}
       |""".stripMargin,
  )

  val formattingOptions = new FormattingOptions(2, true)

  def check(
      name: TestOptions,
      testCase: String,
      expectedCase: String,
  )(implicit loc: Location): Unit = {
    val tripleQuote = "\"\"\""

    def unmangle(string: String): String =
      string.replaceAll("'''", tripleQuote)

    val testCode = unmangle(testCase)
    val base =
      testCode.replace("<<", "").replace(">>", "")
    val expected = unmangle(expectedCase)
    test(name) {
      for {
        _ <- initialize(
          s"""/metals.json
             |{"a":{}}
             |/a/src/main/scala/a/Main.scala
      """.stripMargin + base
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")

        _ <- server.rangeFormatting(
          "a/src/main/scala/a/Main.scala",
          testCode, // with << >>
          expected,
          formattingOptions = Some(formattingOptions),
        )
      } yield ()
    }
  }
}
