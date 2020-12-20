package tests

import scala.meta.internal.docstrings._

import munit.Location

final class ScaladocSuite extends BaseSuite {

  /**
   * Comment does not directly declare a meaningful equality definition, thus
   * this check compares [[Comment.body]] instead.
   */
  def checkCommentBody(name: String, original: String, expected: Body)(implicit
      loc: Location
  ): Unit =
    test(name) {
      val obtained: Comment = ScaladocParser.parseComment(original)
      assertEquals(obtained.body, expected)
    }

  checkCommentBody(
    "Brace ({{{) style code comment",
    """/**{{{val foo: Int = 1}}} */""",
    Body(List(Code("val foo: Int = 1")))
  )

  checkCommentBody(
    "HTML <pre> style code comment",
    """/**<pre>val foo: Int = 1</pre> */""",
    Body(List(Code("val foo: Int = 1")))
  )
}
