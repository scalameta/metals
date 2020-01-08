package tests

import org.eclipse.{lsp4j => l}
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
      r(p(5, 0), p(5, 10)).encloses(p(5, 11)) == false
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

}
