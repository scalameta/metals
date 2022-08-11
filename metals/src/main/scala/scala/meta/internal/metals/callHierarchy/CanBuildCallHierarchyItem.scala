package scala.meta.internal.metals.callHierarchy

import org.eclipse.lsp4j
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.OriginalTree

/** Object that can be used to build call hierarchy item. */
private[callHierarchy] trait CanBuildCallHierarchyItem[A] {
  def symbol: Option[String]
  def range: Option[lsp4j.Range]
}

private[callHierarchy] object CanBuildCallHierarchyItem {
  def unapply(
      item: CanBuildCallHierarchyItem[_]
  ): Option[(String, lsp4j.Range)] =
    (for {
      symbol <- item.symbol
      range <- item.range
    } yield (symbol, range))

  case class SymbolOccurenceCHI(occurence: SymbolOccurrence)
      extends CanBuildCallHierarchyItem[SymbolOccurrence] {
    lazy val symbol = Some(occurence.symbol)
    lazy val range = occurence.range.map(_.toLSP)
  }

  case class SelectTreeCHI(selectTree: SelectTree)
      extends CanBuildCallHierarchyItem[SelectTree] {
    lazy val symbol = selectTree.id.map(_.symbol)
    lazy val range = selectTree match {
      case SelectTree(OriginalTree(range), _) => range.map(_.toLSP)
      case _ => None
    }
  }
}
