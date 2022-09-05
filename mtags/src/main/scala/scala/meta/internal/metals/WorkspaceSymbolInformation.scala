package scala.meta.internal.metals

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.{semanticdb => s}

import org.eclipse.{lsp4j => l}

case class WorkspaceSymbolInformation(
    symbol: String,
    sematicdbKind: s.SymbolInformation.Kind,
    range: l.Range
) {
  def kind: l.SymbolKind = sematicdbKind.toLsp
  def toLsp(uri: String): l.SymbolInformation = {
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
