package tests

object RangeFormattingSuite extends BaseSlowSuite("rangeFormatting") {

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
    "not-valid",
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

  def check(
      name: String,
      testCase: String,
      paste: String,
      expectedCase: String
  ): Unit = {
    val tripleQuote = """\u0022\u0022\u0022"""
    def unmangle(string: String): String =
      string.replaceAll("'''", tripleQuote)

    val test = unmangle(testCase)
    val base = test.replaceAll("(@@)", "")
    val expected = unmangle(expectedCase)
    testAsync(name) {
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
          test, // bez @@
          expected,
          paste
        )
      } yield ()
    }
  }
}
