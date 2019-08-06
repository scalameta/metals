package tests

object OnTypeFormattingSuite extends BaseSlowSuite("onTypeFormatting") {

  val quot = """\u0022\u0022\u0022"""

  check(
    "correct-string",
    s"""
       |object Main {
       |  val str = $quot
       |  #@@word
       |$quot
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = $quot
       |  #
       |  #word
       |$quot
       |}""".stripMargin
  )

  check(
    "after-string",
    s"""
       |object Main {
       |val a = $quot
       |# this is
       |# a multiline
       |# string
       |$quot@@
       |}""".stripMargin,
    s"""
       |object Main {
       |val a = $quot
       |# this is
       |# a multiline
       |# string
       |$quot
       |
       |}""".stripMargin
  )

  check(
    "no-pipe-string",
    s"""
       |object Main {
       |  val abc = 123
       |  val s = $quot example
       |  word@@$quot
       |  abc.toInt
       |}""".stripMargin,
    s"""
       object Main {
       |  val abc = 123
       |  val s = $quot example
       |  word
       |  $quot
       |  abc.toInt
       |}""".stripMargin
  )

  check(
    "far-indent-string",
    s"""
       |object Main {
       |  val str = $quot#@@
       |$quot
       |}""".stripMargin,
    s"""
       |object Main {
       |  val str = $quot#
       |               #
       |$quot
       |}""".stripMargin
  )

  def check(name: String, testCase: String, expectedCase: String): Unit = {
    val test = testCase.replaceAll("#", "|")
    val base = test.replaceAll("(@@)", "")
    val expected = expectedCase.replaceAll("#", "|")
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
          test, // bez @@
          expected
        )
      } yield ()
    }
  }
}
