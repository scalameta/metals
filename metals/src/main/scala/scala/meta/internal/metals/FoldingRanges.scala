package scala.meta.internal.metals

import java.util
import java.util.Collections
import org.eclipse.lsp4j.FoldingRange
import scala.meta.inputs.Position
import scala.meta.internal.metals.FoldingRangeKind.Factory
import scala.meta.internal.metals.FoldingRanges.foldingThreshold

final class FoldingRanges(mode: FoldingMode) {
  private val allRanges = new util.ArrayList[FoldingRange]()

  def get: util.List[FoldingRange] = Collections.unmodifiableList(allRanges)

  def add(factory: Factory, pos: Position): Unit = {
    val range = factory.create(
      pos.startLine,
      pos.startColumn,
      pos.endLine,
      pos.endColumn
    )(mode)

    add(range)
  }

  def add(range: FoldingRange): Unit = {
    if (isNotCollapsed(range)) {
      allRanges.add(range)
    }
  }

  private def isNotCollapsed(range: FoldingRange): Boolean =
    spansMultipleLines(range) || foldsMoreThanThreshold(range)

  private def spansMultipleLines(range: FoldingRange): Boolean =
    range.getStartLine < range.getEndLine

  /**
   * Calling this method makes sense only when range does not spanMultipleLines
   */
  private def foldsMoreThanThreshold(range: FoldingRange): Boolean =
    range.getEndCharacter - range.getStartCharacter > foldingThreshold
}

object FoldingRanges {
  protected val foldingThreshold = 2 // e.g. {}
}
