package tests

import munit.Location

class RangeFormattingWhenSelectingSuite
    extends BaseLspSuite("rangeFormatting") {
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
  )

  // This test shows that currently we don't handle
  // if current selection contains more than one and only one multi-line string
  check(
    "two-string",
    s"""
       |object Main {
       |<<  val firstString = '''
       |                        |first line
       |                            |second line'''.stripMargin
       |
       |  val str2 = '''
       |               |first line
       |               |second line'''.stripMargin>>
       |}""".stripMargin,
    s"""
       |object Main {
       |  val firstString = '''
       |                        |first line
       |                            |second line'''.stripMargin
       |
       |  val str2 = '''
       |               |first line
       |               |second line'''.stripMargin
       |}""".stripMargin
  )

  def check(
      name: String,
      testCase: String,
      expectedCase: String
  )(implicit loc: Location): Unit = {
    val tripleQuote = """\u0022\u0022\u0022"""

    def unmangle(string: String): String =
      string.replaceAll("'''", tripleQuote)

    val testCode = unmangle(testCase)
    val base =
      testCode.replace("<<", "").replace(">>", "")
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
          testCode, // with << >>
          expected
        )
      } yield ()
    }
  }
}
