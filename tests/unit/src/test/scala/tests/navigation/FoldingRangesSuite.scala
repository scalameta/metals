package tests.navigation

import org.eclipse.lsp4j.FoldingRange
import scala.meta.internal.metals.FoldingRanges
import tests.BaseSuite

final class FoldingRangesSuite extends BaseSuite {
  test("preserves-last-line") {
    val ranges = new FoldingRanges(foldOnlyLines = true)

    val lastLine = 10
    val range = new FoldingRange(0, lastLine)

    ranges.add("Region", range)

    assertDiffEqual(range.getEndLine, lastLine - 1)
  }
}
