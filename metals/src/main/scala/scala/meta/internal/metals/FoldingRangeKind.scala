package scala.meta.internal.metals

import org.eclipse.lsp4j.FoldingRange

object FoldingRangeKind {
  abstract class Factory(kind: String) {
    final def create(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    )(mode: FoldingMode): FoldingRange = {
      val range = new FoldingRange(startLine, endLine)
      range.setKind(kind)
      range.setStartCharacter(startColumn)
      range.setEndCharacter(endColumn)
      mode.adjust(range)
      range
    }
  }

  object Region extends Factory("region")
}
