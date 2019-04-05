package scala.meta.internal.metals

import java.util
import java.util.Collections
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeCapabilities
import scala.meta.inputs.Position
import scala.meta.internal.metals.FoldingRangeProvider._
import scala.meta.io.AbsolutePath

final class FoldingRangeProvider(val trees: Trees, foldOnlyLines: Boolean) {
  private val extractor = new FoldingRangeExtractor(foldOnlyLines)

  def getRangedFor(path: AbsolutePath): util.List[FoldingRange] = {
    trees
      .get(path)
      .map(extractor.extract)
      .getOrElse(util.Collections.emptyList())
  }
}

object FoldingRangeProvider {
  val foldingThreshold = 2 // e.g. {}
  val Region = "region"

  def apply(
      trees: Trees,
      capabilities: FoldingRangeCapabilities
  ): FoldingRangeProvider = {
    val foldOnlyLines: Boolean =
      if (capabilities.getLineFoldingOnly == null) false
      else capabilities.getLineFoldingOnly

    new FoldingRangeProvider(trees, foldOnlyLines)
  }
}

final class FoldingRanges(foldOnlyLines: Boolean) {
  private val allRanges = new util.ArrayList[FoldingRange]()

  def get: util.List[FoldingRange] = Collections.unmodifiableList(allRanges)

  def add(kind: String, pos: Position): Unit = {
    import MetalsEnrichments._
    val range = pos.toLSPFoldingRange
    add(kind, range)
  }

  def add(kind: String, range: FoldingRange): Unit = {
    range.setKind(kind)
    add(range, adjust = true)
  }

  def addAsIs(kind: String, range: FoldingRange): Unit = {
    range.setKind(kind)
    add(range, adjust = false)
  }

  def add(range: FoldingRange, adjust: Boolean): Unit = {
    if (isNotCollapsed(range)) {
      if (adjust && foldOnlyLines) {
        range.setEndLine(range.getEndLine - 1) // we want to preserve the last line containing e.g. '}'
      }

      allRanges.add(range)
    }
  }

  // examples of collapsed: "class A {}" or "def foo = {}"
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
