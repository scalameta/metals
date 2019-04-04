package scala.meta.internal.metals

import java.util
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeCapabilities
import scala.meta.Term.Block
import scala.meta.internal.metals.FoldingMode._
import scala.meta.internal.metals.FoldingRangeKind.Factory
import scala.meta.internal.metals.FoldingRangeKind.Region
import scala.meta.io.AbsolutePath
import scala.meta.transversers.SimpleTraverser
import scala.meta.Position
import scala.meta.Template
import scala.meta.Tree

final class FoldingRangeProvider(val trees: Trees, mode: FoldingMode) {
  def getRangedFor(path: AbsolutePath): util.List[FoldingRange] = {
    trees
      .get(path)
      .map(findFoldingRanges)
      .getOrElse(util.Collections.emptyList())
  }

  private def findFoldingRanges(tree: Tree): util.List[FoldingRange] = {
    val ranges = new FoldingRanges
    new RangeFinder(ranges).apply(tree)
    ranges.get
  }

  private class RangeFinder(ranges: FoldingRanges) extends SimpleTraverser {

    private def fold(tree: Tree): Unit = tree match {
      case _: Block => createRange(Region, tree.pos)
      case _: Template => createRange(Region, tree.pos)
      case _ =>
    }

    override def apply(tree: Tree): Unit = {
      fold(tree)
      super.apply(tree)
    }

    private def createRange(factory: Factory, pos: Position): Unit = {
      val range = factory.create(
        pos.startLine,
        pos.startColumn,
        pos.endLine,
        pos.endColumn
      )(mode)

      ranges.add(range)
    }
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
