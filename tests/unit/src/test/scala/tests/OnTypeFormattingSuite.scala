package tests

import scala.concurrent.Future

import munit.Location

class OnTypeFormattingSuite extends BaseLspSuite("onTypeFormatting") {
  private val indent = "  "
  private val escapedNewline = "\\n"
  private val escapedQuote = "\""

  // Ensures that entering a newline at the beginning of a file doesn't
  // throw an exception
  // https://github.com/scalameta/metals/issues/1469

  check(
    "top-of-file",
    s"""|@@
        |object Main {}
        |""".stripMargin,
    s"""|
        |
        |object Main {}
        |""".stripMargin
  )

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
    "multiple-multi1",
    s"""
       |object Main {
       |  val str = '''|@@'''.stripMargin
       |  val other = '''|
       |                 |'''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|
       |               |'''.stripMargin
       |  val other = '''|
       |                 |'''.stripMargin
       |}""".stripMargin
  )

  check(
    "multiple-multi2",
    s"""
       |object Main {
       |  val str = '''|
       |               |'''.stripMargin
       |  val other = '''|@@'''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|
       |               |'''.stripMargin
       |  val other = '''|
       |                 |'''.stripMargin
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
    "interpolated-single-quotes1",
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|@@$$number".stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|" +
       |    s"$$number".stripMargin
       |}""".stripMargin
  )

  check(
    "interpolated-single-quotes2",
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|$$number" + "2@@3$escapedNewline".stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|$$number" + "2" +
       |    "3$escapedNewline".stripMargin
       |}""".stripMargin
  )

  check(
    "interpolated-single-quotes3",
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|$$number" + s"2@@\\3".stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|$$number" + s"2" +
       |    s"\\3".stripMargin
       |}""".stripMargin
  )

  check(
    "interpolated-single-quotes4",
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|$$number" + s"2@@$escapedQuote".stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val number = 102
       |  val str = s"|$$number" + s"2" +
       |    s"$escapedQuote".stripMargin
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
       |$indent
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

  check(
    "after-interpolation-string",
    s"""
       |object Main {
       |  val str = s'''
       |               |word'''.stripMargin@@
       |}
       |""".stripMargin,
    s"""
       |object Main {
       |  val str = s'''
       |               |word'''.stripMargin
       |$indent
       |}
       |""".stripMargin
  )

  check(
    "real-pipe",
    s"""
       |object Main {
       |  val str = '''
       |  |word this is a `|`@@sign
       |  '''.stripMargin
       |}@@""".stripMargin,
    s"""
       |object Main {
       |  val str = '''
       |  |word this is a `|`
       |  |sign
       |  '''.stripMargin
       |}
       |""".stripMargin
  )

  check(
    "string-two-lines",
    s"""
       |object Main {
       |  val str = "test1@@ test2"
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = "test1" +
       |    " test2"
       |}""".stripMargin
  )

  check(
    "string-tree-lines",
    s"""
       |object Main {
       |  val str = "test1" +
       |    "test@@2"
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = "test1" +
       |    "test" +
       |    "2"
       |}""".stripMargin
  )

  check(
    "4-quotes",
    s"""
       |object Main {
       |  val str = '''@@
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = ''''''
       |}""".stripMargin,
    triggerCharacter = "\""
  )
  check(
    "4-quotes-interpolation",
    s"""
       |object Main {
       |  val str = s'''@@
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = s''''''
       |}""".stripMargin,
    triggerCharacter = "\""
  )

  check(
    "add-stripMargin",
    s"""
       |object Main {
       |  val str = '''|@@'''
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|
       |               |'''.stripMargin
       |}""".stripMargin
  )

  check(
    "dont-add-stripMargin",
    s"""
       |object Main {
       |  val str = s'''|@@'''.stripMargin
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = s'''|
       |                |'''.stripMargin
       |}""".stripMargin
  )

  check(
    "add-stripMargin-with-config",
    s"""
       |object Main {
       |  val str = '''|@@'''
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|
       |               |'''
       |}""".stripMargin,
    stripMarginEnabled = false
  )

  check(
    "no-stripMargin",
    s"""
       |object Main {
       |  val str = '''|
       |               |@@'''
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = '''|
       |               |
       |               |'''
       |}""".stripMargin
  )

  def check(
      name: String,
      testCase: String,
      expectedCase: String,
      autoIndent: String = indent,
      triggerCharacter: String = "\n",
      stripMarginEnabled: Boolean = true
  )(implicit loc: Location): Unit = {
    val quote = """\u0022"""
    def unmangle(string: String): String =
      string.replaceAll("'", quote)

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
        _ <-
          if (!stripMarginEnabled)
            server.didChangeConfiguration(
              """{
                |  "enable-strip-margin-on-type-formatting": false
                |}
                |""".stripMargin
            )
          else Future.successful(())
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.onTypeFormatting(
          "a/src/main/scala/a/Main.scala",
          testCode,
          expected,
          autoIndent,
          triggerCharacter
        )
      } yield ()
    }
  }
}
