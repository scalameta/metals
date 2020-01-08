package tests

import org.eclipse.{lsp4j => l}
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.mtags.MtagsEnrichments._

object MtagsEnrichmentsSuite extends BaseSuite {

  test("XtensionLspRange.encloses - single line") {
    def r(start: l.Position, end: l.Position) = new l.Range(start, end)
    def p(line: Int, character: Int) = new l.Position(line, character)
    assert(
      r(p(5, 0), p(5, 10)).encloses(p(5, 0)),
      r(p(5, 0), p(5, 10)).encloses(p(5, 5)),
      r(p(5, 0), p(5, 10)).encloses(p(5, 10)),
      r(p(5, 1), p(5, 10)).encloses(p(5, 0)) == false,
      r(p(5, 0), p(5, 10)).encloses(p(5, 11)) == false,
      r(p(5, 0), p(5, 10)).encloses(p(4, 5)) == false,
      r(p(5, 0), p(5, 10)).encloses(p(6, 5)) == false
    )
  }

  test("XtensionLspRange.encloses - multi line") {
    def r(start: l.Position, end: l.Position) = new l.Range(start, end)
    def p(line: Int, character: Int) = new l.Position(line, character)
    assert(
      r(p(2, 10), p(5, 10)).encloses(p(2, 10)),
      r(p(2, 10), p(5, 10)).encloses(p(3, 0)),
      r(p(2, 10), p(5, 10)).encloses(p(3, 15)),
      r(p(2, 10), p(5, 10)).encloses(p(5, 0)),
      r(p(2, 10), p(5, 10)).encloses(p(5, 10)),
      r(p(2, 10), p(5, 10)).encloses(p(2, 9)) == false,
      r(p(2, 10), p(5, 10)).encloses(p(1, 10)) == false,
      r(p(2, 10), p(5, 10)).encloses(p(5, 11)) == false
    )
  }

  test("XtensionSemanticdbRange.encloses - single line") {
    def r(start: (Int, Int), end: (Int, Int)) =
      s.Range(start._1, start._2, end._1, end._2)
    assert(
      r((5, 0), (5, 10)).encloses(r((5, 0), (5, 10))),
      r((5, 0), (5, 10)).encloses(r((5, 0), (5, 9))),
      r((5, 0), (5, 10)).encloses(r((5, 1), (5, 9))),
      r((5, 0), (5, 10)).encloses(r((5, 1), (5, 10))),
      r((5, 0), (5, 10)).encloses(r((5, 5), (5, 11))) == false,
      r((5, 0), (5, 10)).encloses(r((4, 1), (4, 9))) == false,
      r((5, 0), (5, 10)).encloses(r((6, 1), (6, 9))) == false
    )
  }

  test("XtensionSemanticdbRange.encloses - multi line") {
    def r(start: (Int, Int), end: (Int, Int)) =
      s.Range(start._1, start._2, end._1, end._2)
    assert(
      r((2, 0), (5, 10)).encloses(r((2, 0), (5, 10))),
      r((2, 0), (5, 10)).encloses(r((2, 0), (5, 9))),
      r((2, 0), (5, 10)).encloses(r((2, 1), (5, 9))),
      r((2, 0), (5, 10)).encloses(r((3, 1), (4, 9))),
      r((2, 0), (5, 10)).encloses(r((2, 1), (5, 10))),
      r((2, 0), (5, 10)).encloses(r((5, 5), (5, 11))) == false,
      r((2, 0), (5, 10)).encloses(r((6, 1), (6, 9))) == false,
      r((2, 0), (5, 10)).encloses(r((1, 10), (3, 10))) == false,
      r((2, 0), (5, 10)).encloses(r((1, 10), (6, 10))) == false,
      r((2, 0), (5, 10)).encloses(r((4, 10), (5, 11))) == false,
      r((2, 0), (5, 10)).encloses(r((4, 10), (6, 10))) == false
    )
  }

  test("XtensionSemanticdbRange.encloses(l.Position) - multi line") {
    def r(start: (Int, Int), end: (Int, Int)) =
      s.Range(start._1, start._2, end._1, end._2)
    def p(line: Int, character: Int) = new l.Position(line, character)
    assert(
      r((2, 10), (5, 10)).encloses(p(2, 10)),
      r((2, 10), (5, 10)).encloses(p(3, 0)),
      r((2, 10), (5, 10)).encloses(p(3, 15)),
      r((2, 10), (5, 10)).encloses(p(5, 0)),
      r((2, 10), (5, 10))
        .encloses(p(5, 10), includeLastCharacter = false) == false,
      r((2, 10), (5, 10))
        .encloses(p(5, 10), includeLastCharacter = true) == true,
      r((2, 10), (5, 10)).encloses(p(2, 9)) == false,
      r((2, 10), (5, 10)).encloses(p(1, 10)) == false,
      r((2, 10), (5, 10)).encloses(p(5, 11)) == false
    )
  }

}
