package tests

import org.eclipse.lsp4j.DocumentHighlight

object TestHighlights extends TestHighlights
trait TestHighlights extends RangeReplace {

  def renderAsString(
      code: String,
      highlights: List[DocumentHighlight]
  ): String = {
    highlights.foldLeft(code) { (base, highlight) =>
      replaceInRange(base, highlight.getRange)
    }
  }

}
