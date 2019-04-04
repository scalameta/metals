package scala.meta.internal.metals

import java.util
import java.util.Collections
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeCapabilities
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta._
import scala.meta.inputs.Position
import scala.meta.internal.metals.FoldingRangeProvider._
import scala.meta.io.AbsolutePath

final class FoldingRangeProvider(val trees: Trees, foldOnlyLines: Boolean) {
  def getRangedFor(path: AbsolutePath): util.List[FoldingRange] = {
    trees
      .get(path)
      .map(findFoldingRanges)
      .getOrElse(util.Collections.emptyList())
  }

  private def findFoldingRanges(tree: Tree): util.List[FoldingRange] = {
    val ranges = new FoldingRanges(foldOnlyLines)

    tree traverse {
      case block: Term.Block => ranges.add(Region, block.pos)
      case template: Template => ranges.add(Region, template.pos)
    }

    ranges.get
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
    val range = pos.toLSP(kind)
    add(range)
  }

  def add(range: FoldingRange): Unit = {
    if (isNotCollapsed(range)) {
      if (foldOnlyLines) {
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
