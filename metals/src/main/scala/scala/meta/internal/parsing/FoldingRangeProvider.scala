package scala.meta.internal.parsing

import java.util
import java.util.Collections

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.parsing.FoldingRangeProvider._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.FoldingRange

final class FoldingRangeProvider(
    val trees: Trees,
    buffers: Buffers,
    foldOnlyLines: Boolean,
) {

  def getRangedForScala(
      filePath: AbsolutePath
  ): util.List[FoldingRange] = {
    val result = for {
      code <- buffers.get(filePath)
      if filePath.isScala
      tree <- trees.get(filePath)
    } yield {
      val revised = Input.VirtualFile(filePath.toURI.toString(), code)
      val fromTree =
        Input.VirtualFile(filePath.toURI.toString(), tree.pos.input.text)
      val distance = TokenEditDistance(fromTree, revised, trees).getOrElse(
        TokenEditDistance.NoMatch
      )
      val extractor = new FoldingRangeExtractor(distance, foldOnlyLines)
      extractor.extract(tree)
    }
    result.getOrElse(util.Collections.emptyList())
  }

  def getRangedForJava(
      filePath: AbsolutePath
  ): util.List[FoldingRange] = {
    val result = for {
      code <- buffers.get(filePath)
      if filePath.isJava
    } yield {
      JavaFoldingRangeExtractor.extract(code, filePath, foldOnlyLines).asJava
    }

    result.getOrElse(util.Collections.emptyList())
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

  def add(kind: String, range: FoldingRange, adjust: Boolean): Unit = {
    range.setKind(kind)
    add(range, adjust)
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
