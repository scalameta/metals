package tests.refactor

import munit.Location
import tests.BaseLspSuite

class RangeFormattingSuite extends BaseLspSuite("rangeFormatting") {

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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
  )

  def check(
      name: String,
      testCase: String,
      paste: String,
      expectedCase: String
  )(implicit loc: Location): Unit = {
    val tripleQuote = """\u0022\u0022\u0022"""
    def unmangle(string: String): String =
      string.replaceAll("'''", tripleQuote)

    val testCode = unmangle(testCase)
    val base = testCode.replaceAll("(@@)", "")
    val expected = unmangle(expectedCase)
    test(name) {
      for {
        _ <- server.initialize(
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
          paste
        )
      } yield ()
    }
  }
}
