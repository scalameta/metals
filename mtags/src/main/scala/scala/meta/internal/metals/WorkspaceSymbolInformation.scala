package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.mtags.MtagsEnrichments._

case class WorkspaceSymbolInformation(
    symbol: String,
    sematicdbKind: s.SymbolInformation.Kind,
    range: l.Range
) {
  def kind: l.SymbolKind = sematicdbKind.toLSP
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
