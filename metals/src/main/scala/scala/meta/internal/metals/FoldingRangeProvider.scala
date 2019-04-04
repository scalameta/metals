package scala.meta.internal.metals

import java.util
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeCapabilities
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta._
import scala.meta.internal.metals.FoldingMode._
import scala.meta.internal.metals.FoldingRangeKind.Region
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
