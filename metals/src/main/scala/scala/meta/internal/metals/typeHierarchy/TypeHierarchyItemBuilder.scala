package scala.meta.internal.metals.typeHierarchy

import scala.annotation.tailrec

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem

private[typeHierarchy] final class TypeHierarchyItemBuilder {

  def build(
      symbol: String,
      info: Option[SymbolInformation],
      source: AbsolutePath,
      range: Range,
      selectionRange: Range,
  ): TypeHierarchyItem = {
    val name = info.map(_.displayName).getOrElse(symbol.desc.name.value)
    val kind = info.map(_.kind.toLsp).getOrElse(SymbolKind.Class)
    val detail = extractPackage(symbol)

    val item = new TypeHierarchyItem(
      name,
      kind,
      source.toURI.toString,
      range,
      selectionRange,
    )
    item.setDetail(detail)
    item.setData(TypeHierarchyItemInfo(symbol))
    item
  }

  private def extractPackage(symbol: String): String = {
    @tailrec
    def loop(s: String): String =
      if (s.isPackage || s.isNone) s
      else loop(s.owner)

    val pkg = loop(symbol.owner)
    if (pkg.isNone || pkg == Symbols.EmptyPackage) ""
    else pkg.stripSuffix("/").replace("/", ".")
  }
}
