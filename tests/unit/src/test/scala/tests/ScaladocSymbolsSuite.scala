package tests

import scala.meta.internal.metals.ContextSymbols
import scala.meta.internal.metals.ScalaDocLink

import munit.TestOptions

class ScaladocSymbolsSuite extends BaseSuite {

  check(
    "class-scaladoc-link",
    "b.O.B!",
    List("a/b/O.B#", "b/O.B#"),
  )

  check(
    "local-scaladoc-link",
    "O.B!",
    List("a/A.O.B#", "a/O.B#"),
  )

  check(
    "object-scaladoc-link",
    "c.b.O.B$",
    List("a/c/b/O.B.", "a/c/b/O.B(+n).", "c/b/O.B.", "c/b/O.B(+n)."),
  )

  check(
    "method-scaladoc-link",
    "c.b.O.foo(a : Int): String",
    List("a/c/b/O.foo(+n).", "c/b/O.foo(+n)."),
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
      "a/`this.b`/`B.B`#", "a/`this.b`/`B.B`.", "a/`this.b`/`B.B`(+n).",
      "`this.b`/`B.B`#", "`this.b`/`B.B`.", "`this.b`/`B.B`(+n).",
    ),
  )

  check(
    "escape2-scaladoc-link",
    "this\\.B",
    List(
      "a/A.this\\.B#", "a/A.this\\.B.", "a/A.this\\.B(+n).", "a/this\\.B#",
      "a/this\\.B.", "a/this\\.B(+n).",
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
