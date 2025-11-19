package tests

import scala.meta.internal.metals.SymbolAlternatives
import scala.meta.internal.mtags.Symbol

import munit.FunSuite
import munit.Location
import munit.TestOptions

class AlternativeSymbolsSuite extends FunSuite {
  def check(
      sym: TestOptions,
      expected: String,
  )(implicit loc: Location): Unit = {
    test(sym) {
      val result =
        SymbolAlternatives.expand(Symbol(sym.name), maxConstructors = 2)
      assertNoDiff(result.mkString("\n"), expected)
    }
  }

  check(
    "java/Foo#",
    """|java/Foo#
       |java/Foo#`<init>`().
       |java/Foo#`<init>`(+1).
       |""".stripMargin,
  )

}
