package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j
import org.eclipse.lsp4j.Location

class PcReferencesProvider(
    driver: InteractiveDriver,
    params: OffsetParams,
    targetFiles: List[VirtualFileParams],
    includeDefinition: Boolean,
):
  val symbolSearch = new WithCompilationUnit(driver, params) with PcSymbolSearch
  def result(): List[Location] =
    for
      (sought, _) <- symbolSearch.soughtSymbols.toList
      params <- targetFiles
      collected <- collectForFile(sought, params)
    yield collected

  private def collectForFile(sought: Set[Symbol], params: VirtualFileParams) =
    new WithCompilationUnit(driver, params)
      with PcCollector[Option[lsp4j.Range]]:
      def collect(parent: Option[Tree])(
          tree: Tree | EndMarker,
          toAdjust: SourcePosition,
          symbol: Option[Symbol],
      ): Option[lsp4j.Range] =
        val (pos, _) = toAdjust.adjust(text)
        tree match
          case (_: DefTree) if !includeDefinition => None
          case _: Tree => Some(pos.toLsp)
          case _ => None
    .resultWithSought(sought)
      .flatten
      .map(new Location(params.uri().toString(), _))
end PcReferencesProvider
