package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
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
  def result(): List[l.Location] =
    for {
      (sought, _) <- soughtSymbols.toList
      params <- targetFiles
      collected <- {
        val collector = new WithCompilationUnit(compiler, params)
          with PcCollector[Option[l.Range]] {
          import compiler._
          override def collect(parent: Option[Tree])(
              tree: Tree,
              toAdjust: Position,
              sym: Option[compiler.Symbol]
          ): Option[l.Range] = {
            val (pos, _) = toAdjust.adjust(text)
            tree match {
              case _: DefTree if !includeDefinition => None
              case _ => Some(pos.toLsp)
            }
          }
        }

        collector
          .resultWithSought(sought.asInstanceOf[Set[collector.compiler.Symbol]])
          .flatten
          .map(new l.Location(params.uri().toString(), _))
      }
    } yield collected
}
