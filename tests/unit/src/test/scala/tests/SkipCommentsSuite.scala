package tests

import scala.meta.internal.metals.MetalsEnrichments._

import munit.TestOptions

class SkipCommentsSuite extends munit.FunSuite with Assertions {

  check(
    "drop-all",
    "  /* bhjv */ /* bhjbi */",
  )

  check(
    "between-comments",
    "  /* bhjv */ @ /* bhjbi */",
    Some('@'),
  )

  check(
    "not-comment",
    "  /@",
    Some('/'),
  )

  check(
    "tab",
    "\t /*cbu * whec*/",
  )

  def check(
      name: TestOptions,
      text: String,
      nextChar: Option[Char] = None,
  ): Unit =
    test(name) {
      val index = indexAfterSpacesAndComments(text.toCharArray())
      def clue = s"text: $text, index: $index"
      nextChar match {
        case None => assert(index < 0 || index >= text.size, clue)
        case Some(c) =>
          assertEquals(text.charAt(index), c, clue)
      }
    }

}
