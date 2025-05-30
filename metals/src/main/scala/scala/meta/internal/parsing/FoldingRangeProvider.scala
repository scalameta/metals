package scala.meta.internal.parsing

import java.util
import java.util.Collections

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.parsing.FoldingRangeProvider._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.FoldingRange

final class FoldingRangeProvider(
    val trees: Trees,
    buffers: Buffers,
    foldOnlyLines: Boolean,
    foldingRageMinimumSpan: Int,
    scalaVersionSelector: ScalaVersionSelector,
) {

  def getRangedForScala(
      filePath: AbsolutePath
  ): util.List[FoldingRange] = {
    val result = for {
      tree <- trees.get(filePath)
      if filePath.isScala
    } yield {
      val distance = buffers.tokenEditDistance(
        filePath,
        tree.pos.input.text,
        scalaVersionSelector,
      )
      val extractor = new FoldingRangeExtractor(
        distance,
        foldOnlyLines,
        foldingRageMinimumSpan,
      )
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
      JavaFoldingRangeExtractor
        .extract(code, filePath, foldOnlyLines, foldingRageMinimumSpan)
        .asJava
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
