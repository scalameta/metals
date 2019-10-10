package scala.meta.internal.metals

import java.util
import java.util.Collections
import org.eclipse.lsp4j.FoldingRange
import scala.meta.inputs.Position
import scala.meta.internal.metals.FoldingRangeProvider._
import scala.meta.inputs.Input

final class FoldingRangeProvider(
    val trees: Trees,
    foldOnlyLines: Boolean
) {

  def getRangedFor(fileName: String, code: String): util.List[FoldingRange] = {
    trees
      .get(fileName, code)
      .map(tree => {
        val revised = Input.VirtualFile(fileName, code)
        val fromTree = Input.VirtualFile(fileName, tree.pos.input.text)
        val distance = TokenEditDistance(fromTree, revised)
        val extractor = new FoldingRangeExtractor(distance, foldOnlyLines)
        extractor.extract(tree)
      })
      .getOrElse(util.Collections.emptyList())
  }
}

object FoldingRangeProvider {
  val foldingThreshold = 2
}

final class FoldingRanges(foldOnlyLines: Boolean) {
  private val allRanges = new util.ArrayList[FoldingRange]()

  def get: util.List[FoldingRange] = Collections.unmodifiableList(allRanges)

  def add(kind: String, pos: Position): Unit = {
    val range = createRange(pos)
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
        range.setEndLine(
          range.getEndLine - 1
        ) // we want to preserve the last line containing e.g. '}'
      }

      allRanges.add(range)
    }
  }

  private def createRange(pos: Position): FoldingRange = {
    val range = new FoldingRange(pos.startLine, pos.endLine)
    range.setStartCharacter(pos.startColumn)
    range.setEndCharacter(pos.endColumn)
    range
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
