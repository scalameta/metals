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
import scala.meta.internal.metals.FoldingMode._
import scala.meta.internal.metals.FoldingRangeKind.Factory
import scala.meta.internal.metals.FoldingRangeKind.Region
import scala.meta.internal.metals.FoldingRanges.foldingThreshold
import scala.meta.io.AbsolutePath

final class FoldingRangeProvider(val trees: Trees, mode: FoldingMode) {
  def getRangedFor(path: AbsolutePath): util.List[FoldingRange] = {
    trees
      .get(path)
      .map(findFoldingRanges)
      .getOrElse(util.Collections.emptyList())
  }

  private def findFoldingRanges(tree: Tree): util.List[FoldingRange] = {
    val ranges = new FoldingRanges(mode)

    tree traverse {
      case block: Term.Block => ranges.add(Region, block.pos)
      case template: Template => ranges.add(Region, template.pos)
    }

    ranges.get
  }
}

object FoldingRangeProvider {
  def apply(
      trees: Trees,
      capabilities: FoldingRangeCapabilities
  ): FoldingRangeProvider = {
    val mode =
      if (capabilities.getLineFoldingOnly) FoldLines
      else FoldCharacters

    new FoldingRangeProvider(trees, mode)
  }
}

trait FoldingMode {
  def adjust(range: FoldingRange): Unit
}

object FoldingMode {
  object FoldLines extends FoldingMode {
    override def adjust(range: FoldingRange): Unit = preserveLastLine(range)

    private def preserveLastLine(range: FoldingRange): Unit = {
      val adjustedEndLine = range.getEndLine - 1 // we want to preserve the last line containing e.g. '}'
      range.setEndLine(adjustedEndLine)
    }
  }

  object FoldCharacters extends FoldingMode {
    override def adjust(range: FoldingRange): Unit = {}
  }
}

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

object FoldingRanges {
  protected val foldingThreshold = 2 // e.g. {}
}

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
