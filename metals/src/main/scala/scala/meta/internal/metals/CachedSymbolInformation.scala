package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.internal.{semanticdb => s}
import MetalsEnrichments._

case class CachedSymbolInformation(
    symbol: String,
    kind: l.SymbolKind,
    range: l.Range
) {
  def toLSP(uri: String): l.SymbolInformation = {
    import scala.meta.internal.semanticdb.Scala._
    val (desc, owner) = DescriptorParser(symbol)
    new l.SymbolInformation(
      desc.name.value,
      kind,
      new l.Location(uri, range),
      owner.replace('/', '.')
    )
  }
}

object CachedSymbolInformation {
  def fromDefn(defn: SemanticdbDefinition): CachedSymbolInformation = {
    val range = defn.occ.range.getOrElse(s.Range())
    CachedSymbolInformation(defn.info.symbol, defn.info.kind.toLSP, range.toLSP)
  }
}
