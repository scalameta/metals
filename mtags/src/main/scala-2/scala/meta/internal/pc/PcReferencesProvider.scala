package scala.meta.internal.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

import org.eclipse.{lsp4j => l}

class PcReferencesProvider(
    compiler: MetalsGlobal,
    params: OffsetParams,
    targetFiles: List[VirtualFileParams],
    includeDefinition: Boolean
) extends WithCompilationUnit(compiler, params)
    with PcSymbolSearch {
  def result(): List[DefinitionResult] = {
    val result = for {
      (sought, _) <- soughtSymbols.toList
      params <-
        if (sought.forall(_.isLocalToBlock))
          targetFiles.find(_.uri() == params.uri()).toList
        else targetFiles
      collected <- {
        val collector = new WithCompilationUnit(compiler, params)
          with PcCollector[Option[(String, l.Range)]] {
          import compiler._
          override def collect(parent: Option[Tree])(
              tree: Tree,
              toAdjust: Position,
              sym: Option[compiler.Symbol]
          ): Option[(String, l.Range)] = {
            val (pos, _) = toAdjust.adjust(text)
            tree match {
              case _: DefTree if !includeDefinition => None
              case t => Some(compiler.semanticdbSymbol(t.symbol), pos.toLsp)
            }
          }
        }

        collector
          .resultWithSought(sought.asInstanceOf[Set[collector.compiler.Symbol]])
          .flatten
          .map { case (symbol, range) =>
            (symbol, new l.Location(params.uri().toString(), range))
          }
      }
    } yield collected
    result
      .groupBy(_._1)
      .map { case (symbol, locs) =>
        DefinitionResultImpl(symbol, locs.map(_._2).asJava)
      }
      .toList
  }
}
