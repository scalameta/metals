package tests.rangeFormatting

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.FormattingOptions
import tests.BaseLspSuite

class ScalaCliDependencyRangeFormatterPastingSuite
    extends BaseLspSuite("MillifyRangeFormatting") {

  check(
    "change-dep-format-on-paste",
    s"""
       |//> using dep @@
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using dep org.scalameta::munit:0.7.26
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "change-dep-format-within-existing-deps",
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep @@
       |//> using dep com.lihaoyi::pprint::0.6.6
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep org.scalameta::munit:0.7.26
       |//> using dep com.lihaoyi::pprint::0.6.6
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "not-change-format-outside-using-directive",
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep com.lihaoyi::pprint::0.6.6
       |// TODO: @@
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep com.lihaoyi::pprint::0.6.6
       |// TODO: "org.scalameta" %% "munit" % "0.7.26"
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  val formattingOptions = new FormattingOptions(2, true)

  // TODO this function should be deduplicated across suites
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
             |""".stripMargin + base
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
