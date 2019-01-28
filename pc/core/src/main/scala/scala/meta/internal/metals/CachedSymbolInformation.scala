package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}

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
