package tests

import org.eclipse.lsp4j.FoldingRange

import scala.meta.internal.metals.FoldingMode

final class LineFoldingModeSuite extends BaseSuite {
  test("preserves-last-line") {
    val lastLine = 10
    val range = new FoldingRange(0, lastLine)

    FoldingMode.FoldLines.adjust(range)

    assertEquals(range.getEndLine, lastLine - 1)
  }
}
