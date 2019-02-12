package tests

import scala.language.postfixOps
import scala.meta.internal.docstrings.MarkdownGenerator

object ScaladocSuite extends BaseSuite {

  def check(name: String, original: String, expected: String): Unit = {
    test(name) {
      val obtained = MarkdownGenerator.toMarkdown(original)
      assertNoDiff(obtained.mkString("\n"), expected)
    }
  }

  check(
    "tags",
    """/**
      | *  implicits for converting to and from `Int`.
      | *
      | *  ==Overview==
      | *  The main class to use is [[my.package.complex.Complex]], as so
      | *  {{{
      | *  scala> val complex = Complex(4,3)
      | *  complex: my.package.complex.Complex = 4 + 3i
      | *  }}}
      | *  ---
      | *  If you include [[my.package.complex.ComplexConversions]], you can
      | *  convert numbers more directly
      | *  {{{
      | *  scala> import my.package.complex.ComplexConversions._
      | *  scala> val complex = 4 + 3.i
      | *  complex: my.package.complex.Complex = 4 + 3i
      | *  }}}
      | */
      |}
    """.stripMargin,
    """
      |@param a A number
      |""".stripMargin
  )

}
