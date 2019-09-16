package tests

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.Location

object TestRanges extends RangeReplace {

  def renderHighlightsAsString(
      code: String,
      highlights: List[DocumentHighlight]
  ): String = {
    highlights.foldLeft(code) { (base, highlight) =>
      replaceInRange(base, highlight.getRange)
    }
  }

  def renderLocationsAsString(
      code: String,
      locations: List[Location]
  ): String = {
    locations.foldLeft(code) { (base, highlight) =>
      replaceInRange(base, highlight.getRange)
    }
  }
}
