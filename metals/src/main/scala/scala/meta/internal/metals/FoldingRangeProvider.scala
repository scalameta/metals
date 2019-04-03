package scala.meta.internal.metals

import java.util

import org.eclipse.lsp4j.{FoldingRange, FoldingRangeCapabilities}

import scala.meta.Term.Block
import scala.meta.internal.metals.FoldingMode._
import scala.meta.internal.metals.FoldingRanges.{Factory, Region}
import scala.meta.io.AbsolutePath
import scala.meta.transversers.SimpleTraverser
import scala.meta.{Position, Tree}

final class FoldingRangeProvider(val trees: Trees, mode: FoldingMode) {
  def getRangedFor(path: AbsolutePath): util.List[FoldingRange] = {
    trees
      .get(path)
      .map(findFoldingRanges)
      .getOrElse(util.Collections.emptyList())
  }

  private def findFoldingRanges(tree: Tree): util.List[FoldingRange] = {
    val ranges = new util.ArrayList[FoldingRange]()
    new RangeFinder(ranges).apply(tree)
    ranges
  }

  private class RangeFinder(ranges: util.ArrayList[FoldingRange])
      extends SimpleTraverser {

    private def fold(tree: Tree): Unit = tree match {
      case Block(_) => createRange(Region, tree.pos)
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
