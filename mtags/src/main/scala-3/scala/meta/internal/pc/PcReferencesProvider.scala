package scala.meta.internal.pc

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*
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
  def result(): List[DefinitionResult] =
    val result =
      for
        (sought, _) <- symbolSearch.soughtSymbols.toList
        params <-
          given Context = symbolSearch.ctx

          if sought.forall(_.is(Flags.Local))
          then targetFiles.find(_.uri() == params.uri()).toList
          else targetFiles

        collected <- collectForFile(sought, params)
      yield collected
    result
      .groupBy(_._1)
      .map { case (symbol, locs) =>
        DefinitionResultImpl(symbol, locs.map(_._2).asJava)
      }
      .toList
  end result

  private def collectForFile(sought: Set[Symbol], params: VirtualFileParams) =
    new WithCompilationUnit(driver, params)
      with PcCollector[Option[(String, lsp4j.Range)]]:
      def collect(parent: Option[Tree])(
          tree: Tree | EndMarker,
          toAdjust: SourcePosition,
          symbol: Option[Symbol],
      ): Option[(String, lsp4j.Range)] =
        val (pos, _) = toAdjust.adjust(text)
        tree match
          case (_: DefTree) if !includeDefinition => None
          case t: Tree =>
            val sym = symbol.getOrElse(t.symbol)
            Some(SemanticdbSymbols.symbolName(sym), pos.toLsp)
          case _ => None
    .resultWithSought(sought)
      .flatten
      .map { case (symbol, range) =>
        (symbol, new Location(params.uri().toString(), range))
      }
end PcReferencesProvider
