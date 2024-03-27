package scala.meta.internal.pc

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.Buffers
import scala.meta.pc.PcAdjustFileParams
import scala.meta.pc.ReferencesRequest
import scala.meta.pc.ReferencesResult

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
    request: ReferencesRequest,
    buffers: Buffers,
    scalaVersion: String,
):
  val symbolSearch = new WithCompilationUnit(driver, request.params())
    with PcSymbolSearch
  @volatile var isCancelled: Boolean = false
  def result(): List[ReferencesResult] =
    val allLocations =
      symbolSearch.soughtSymbols match
        case Some((sought, _)) if sought.nonEmpty =>
          given Context = symbolSearch.ctx
          if sought.forall(_.is(Flags.Local)) then
            val file = buffers.getFile(request.params().uri(), scalaVersion)
            if file.isPresent()
            then collectForFile(sought, file.get())
            else List.empty
          else
            val fileUris = request.targetUris().asScala.toList
            fileUris.flatMap { uri =>
              val file = buffers.getFile(uri, scalaVersion)
              if file.isPresent() && !isCancelled then
                collectForFile(sought, file.get())
              else List.empty
            }
          end if
        case _ => List.empty

    allLocations
      .groupBy(_._1)
      .map { case (symbol, locs) =>
        ReferencesResultImpl(symbol, locs.map(_._2).asJava)
      }
      .toList
  end result

  private def collectForFile(
      sought: Set[Symbol],
      adjustParams: PcAdjustFileParams,
  ) =
    val collector = new WithCompilationUnit(driver, adjustParams.params())
      with PcCollector[Option[(String, lsp4j.Range)]]:
      def collect(parent: Option[Tree])(
          tree: Tree | EndMarker,
          toAdjust: SourcePosition,
          symbol: Option[Symbol],
      ): Option[(String, lsp4j.Range)] =
        val (pos, _) = toAdjust.adjust(text)
        tree match
          case _: DefTree if !request.includeDefinition() => None
          case t: Tree =>
            val sym = symbol.getOrElse(t.symbol)
            Some(SemanticdbSymbols.symbolName(sym), pos.toLsp)
          case _ => None
    val results =
      collector
        .resultWithSought(sought)
        .flatten
        .map { case (symbol, range) =>
          (
            symbol,
            adjustParams.adjustLocation(
              new Location(collector.uri.toString(), range)
            ),
          )
        }
    driver.close(collector.uri)
    results
  end collectForFile
end PcReferencesProvider
