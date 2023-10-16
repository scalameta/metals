package tests

import scala.meta.internal.metals.ContextSymbols
import scala.meta.internal.metals.ScalaDocLink

import munit.TestOptions

class ScaladocSymbolsSuite extends BaseSuite {

  check(
    "class-scaladoc-link",
    "a.b.O.B!",
    List("a/b/O.B#"),
  )

  check(
    "local-scaladoc-link",
    "O.B!",
    List("O.B#", "a/A.O.B#", "a/O.B#"),
  )

  check(
    "object-scaladoc-link",
    "a.b.O.B$",
    List("a/b/O.B.", "a/b/O.B(+n)."),
  )

  check(
    "method-scaladoc-link",
    "a.b.O.foo(a : Int): String",
    List("a/b/O.foo(+n)."),
  )

  check(
    "this-scaladoc-link",
    "this.B",
    List("a/A.B#", "a/A.B.", "a/A.B(+n)."),
  )

  check(
    "escape-scaladoc-link",
    "`this.b`.`B.B`",
    List(
      "`this.b`/`B.B`#",
      "`this.b`/`B.B`.",
      "`this.b`/`B.B`(+n).",
    ),
  )

  check(
    "escape2-scaladoc-link",
    "this\\.B",
    List(
      "this\\.B#", "this\\.B.", "this\\.B(+n).", "a/A.this\\.B#",
      "a/A.this\\.B.", "a/A.this\\.B(+n).", "a/this\\.B#", "a/this\\.B.",
      "a/this\\.B(+n).",
    ),
  )

  def check(
      name: TestOptions,
      symbol: String,
      expected: List[String],
      contextSymbols: ContextSymbols = ContextSymbols("a/", "A.", None),
  ): Unit =
    test(name) {
      assertEquals(
        ScalaDocLink(symbol)
          .toScalaMetaSymbols(contextSymbols)
          .map(_.showSymbol),
        expected,
      )
    }

}
