package tests

import munit.Location

class OnTypeFormattingSuite extends BaseLspSuite("onTypeFormatting") {

  check(
    "correct-string",
    s"""
       |object Main {
       |  val str = '''
       |  |@@word
       |  '''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |
       |  |word
       |  '''.stripMargin
       |}""".stripMargin
  )

  check(
    "interpolated-string",
    s"""
       |object Main {
       |  val number = 102
       |  val str = s'''
       |  |$$number
       |  |@@word
       |  '''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val number = 102
       |  val str = s'''
       |  |$$number
       |  |
       |  |word
       |  '''.stripMargin
       |}""".stripMargin
  )

  check(
    "multi-interpolated",
    s"""
       |object Main {
       |  val number = 102
       |  val other = s'''
       |  |$$number
       |  |word
       |  '''.stripMargin
       |  val str = s'''
       |  |$$number
       |  |@@word
       |  '''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val number = 102
       |  val other = s'''
       |  |$$number
       |  |word
       |  '''.stripMargin
       |  val str = s'''
       |  |$$number
       |  |
       |  |word
       |  '''.stripMargin
       |}""".stripMargin
  )

  check(
    "interpolated-single-quotes",
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|@@$$number".stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|
       |  $$number".stripMargin
       |}""".stripMargin
  )

  check(
    "correct-no-dot",
    s"""
       |object Main {
       |  val str = '''
       |  |@@word
       |  ''' stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |
       |  |word
       |  ''' stripMargin
       |}""".stripMargin
  )

  check(
    "after-string",
    s"""
       |object Main {
       |  val a = '''
       |  | this is
       |  | a multiline
       |  | string
       |  '''.stripMargin@@
       |}""".stripMargin,
    s"""
       |object Main {
       |  val a = '''
       |  | this is
       |  | a multiline
       |  | string
       |  '''.stripMargin
       |  
       |}""".stripMargin
  )

  check(
    "no-pipe-string",
    s"""
       |object Main {
       |  val abc = 123
       |  val s = ''' example
       |  word@@'''.stripMargin
       |  abc.toInt
       |}""".stripMargin,
    s"""
       object Main {
       |  val abc = 123
       |  val s = ''' example
       |  word
       |  '''.stripMargin
       |  abc.toInt
       |}""".stripMargin
  )

  check(
    "far-indent-string",
    s"""
       |object Main {
       |  val str = '''|@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|
       |               |
       |  '''.stripMargin
       |}""".stripMargin
  )

  // this can be caused by the client if scala syntax is recognized inside a string
  check(
    "weird-indent",
    s"""
       |object Main {
       |  val str = '''
       |  |object A{@@
       |  '''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |object A{
       |  |
       |  '''.stripMargin
       |}""".stripMargin,
    " " * 4
  )

  check(
    "last",
    s"""
       |object Main {
       |  val str = '''
       |  |word
       |  '''.stripMargin
       |}@@""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |word
       |  '''.stripMargin
       |}
       |""".stripMargin
  )

  def check(
      name: String,
      testCase: String,
      expectedCase: String,
      autoIndent: String = "  "
  )(implicit loc: Location): Unit = {
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
        _ <- server.onTypeFormatting(
          "a/src/main/scala/a/Main.scala",
          test,
          expected,
          autoIndent
        )
      } yield ()
    }
  }
}
