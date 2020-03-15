package scala.meta.internal.metals

import java.util
import java.util.Collections
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.InitializeParams
import scala.meta.inputs.Position
import scala.meta.internal.metals.FoldingRangeProvider._
import scala.meta.io.AbsolutePath

final class FoldingRangeProvider(
    val trees: Trees,
    val buffers: Buffers,
    foldOnlyLines: Boolean
) {

  def getRangedFor(path: AbsolutePath): util.List[FoldingRange] = {
    trees
      .get(path)
      .map(tree => {
        val distance =
          TokenEditDistance.fromBuffer(path, tree.pos.input.text, buffers)
        val extractor = new FoldingRangeExtractor(distance, foldOnlyLines)
        extractor.extract(tree)
      })
      .getOrElse(util.Collections.emptyList())
  }
}

object FoldingRangeProvider {
  val foldingThreshold = 2

  def apply(
      trees: Trees,
      buffers: Buffers,
      params: InitializeParams
  ): FoldingRangeProvider = {
    val settings = for {
      capabilities <- Option(params.getCapabilities)
      textDocument <- Option(capabilities.getTextDocument)
      settings <- Option(textDocument.getFoldingRange)
    } yield settings

    val foldOnlyLines = settings
      .map(_.getLineFoldingOnly)
      .contains(true)

    new FoldingRangeProvider(trees, buffers, foldOnlyLines)
  }
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
