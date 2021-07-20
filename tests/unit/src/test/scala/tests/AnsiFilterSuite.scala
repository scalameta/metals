package tests

import scala.meta.internal.ansi.AnsiFilter

import munit.TestOptions

class AnsiFilterSuite extends BaseSuite {

  check("ansi", fansi.Color.Blue("blue").toString, "blue")
  check("emoji", "🇮🇸🇵🇱", "🇮🇸🇵🇱")

  def check(name: TestOptions, in: String, expected: String): Unit =
    test(name) {
      assertDiffEqual(AnsiFilter()(in), expected)
    }
}
