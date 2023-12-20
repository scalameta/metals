package tests.rangeFormatting

import scala.meta.internal.metals.UserConfiguration

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.FormattingOptions
import tests.BaseLspSuite

class MultilineStringRangeFormattingWhenPastingSuite
    extends BaseLspSuite("rangeFormatting") {
  val formattingOptions: FormattingOptions = new FormattingOptions(
    /** tabSize: */
    2,
    /** insertSpaces */
    true,
  )

  override def userConfig: UserConfiguration =
    super.userConfig.copy(enableIndentOnPaste = true)

  check(
    "lines",
    s"""
       |object Main {
       |  val str = '''
       |  |@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""|first line
        |second line
        | different indent""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |first line
       |  |second line
       |  | different indent
       |  '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "lines-moved-start",
    s"""
       |object Main {
       |  val str = '''
       |  |      @@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""|first line
        |second line
        | different indent""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |      first line
       |  |second line
       |  | different indent
       |  '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "single-line",
    s"""
       |object Main {
       |  val str = '''
       |  |@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""|first line""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |first line
       |  '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "multiple-multi-single1",
    s"""
       |object Main {
       |  val str = '''
       |              |@@
       |              |'''.stripMargin
       |  val other = '''
       |                |
       |                |'''.stripMargin
       |}""".stripMargin,
    s"""|  some text""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |              |  some text
       |              |'''.stripMargin
       |  val other = '''
       |                |
       |                |'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "multiple-multi-single2",
    s"""
       |object Main {
       |  val str = '''
       |              |
       |              |'''.stripMargin
       |  val other = '''
       |                |@@
       |                |'''.stripMargin
       |}""".stripMargin,
    s"""|  some text""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |              |
       |              |'''.stripMargin
       |  val other = '''
       |                |  some text
       |                |'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "multiple-multi-double1",
    s"""
       |object Main {
       |  val str = '''
       |              |@@
       |              |'''.stripMargin
       |  val other = '''
       |                |
       |                |'''.stripMargin
       |}""".stripMargin,
    s"""|some text
        |  some other text
        |""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |              |some text
       |              |  some other text
       |              |
       |              |'''.stripMargin
       |  val other = '''
       |                |
       |                |'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "multiple-multi-double2",
    s"""
       |object Main {
       |  val str = '''
       |              |
       |              |'''.stripMargin
       |  val other = '''
       |                |@@
       |                |'''.stripMargin
       |}""".stripMargin,
    s"""|  some text
        |some other text
        |""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |              |
       |              |'''.stripMargin
       |  val other = '''
       |                |  some text
       |                |some other text
       |                |
       |                |'''.stripMargin
       |}""".stripMargin,
  )

  check(
    "paste-on-first-line-with-pipe",
    s"""
       |object Main {
       |  val str = '''| hi @@
       |               |
       |               '''.stripMargin
       |}""".stripMargin,
    s"""|first line""".stripMargin,
    s"""
       |object Main {
       |  val str = '''| hi first line
       |               |
       |               '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "paste-on-first-line-without-pipe",
    s"""
       |object Main {
       |  val str = ''' hi @@
       |              |
       |              '''.stripMargin
       |}""".stripMargin,
    s"""|first line""".stripMargin,
    s"""
       |object Main {
       |  val str = ''' hi first line
       |              |
       |              '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "without-stripmargin",
    s"""
       |object Main {
       |  val str = '''
       |  |@@
       |  '''
       |}""".stripMargin,
    s"""|first line
        |second line
        | different indent""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |first line
       |second line
       | different indent
       |  '''
       |}""".stripMargin,
  )

  check(
    "with-pipe",
    s"""
       |object Main {
       |  val str = '''
       |  |@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""| |single line
        |""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |single line
       |  |
       |  '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "with-pipes",
    s"""
       |object Main {
       |  val str = '''
       |  |@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""| |first line
        | |second line
        | | different indent""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |first line
       |  |second line
       |  | different indent
       |  '''.stripMargin
       |}""".stripMargin,
  )
  check(
    "with-wrong-indentation",
    s"""
       |object Main {
       |  val str = '''
       |  |
       |      |@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""| |first line
        |
        | |second line""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |
       |      |first line
       |      |
       |      |second line
       |  '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "with-pipes-skip-line",
    s"""
       |object Main {
       |  val str = '''
       |  |@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""| |first line
        |
        | |second line""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |first line
       |  |
       |  |second line
       |  '''.stripMargin
       |}""".stripMargin,
  )

  check(
    "pasting-after-interpolation",
    s"""
       |object Main {
       |  val str = s'''
       |               |ok'''.stripMargin
       |  @@
       |}""".stripMargin,
    s"""
       |  val other = '''
       |              |  some text
       |              |'''.stripMargin""".stripMargin,
    s"""
       |object Main {
       |  val str = s'''
       |               |ok'''.stripMargin
       |
       |  val other = '''
       |              |  some text
       |              |'''.stripMargin
       |}""".stripMargin,
  )

  def check(
      name: TestOptions,
      testCase: String,
      paste: String,
      expectedCase: String,
  )(implicit loc: Location): Unit = {
    val tripleQuote = "\"\"\""
    def unmangle(string: String): String =
      string.replaceAll("'''", tripleQuote)

    val testCode = unmangle(testCase)
    val base = testCode.replaceAll("(@@)", "")
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
          testCode, // bez @@
          expected,
          unmangle(paste),
          workspace,
          Some(formattingOptions),
        )
      } yield ()
    }
  }
}
