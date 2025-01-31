package tests

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.FormattingOptions

abstract class BaseRangeFormatterSuite(name: String)
    extends BaseLspSuite(name) {
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

  val formattingOptions = new FormattingOptions(2, true)
}
