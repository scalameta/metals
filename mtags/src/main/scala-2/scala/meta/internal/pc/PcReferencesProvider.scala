package scala.meta.internal.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.Buffers
import scala.meta.pc.PcAdjustFileParams
import scala.meta.pc.ReferencesRequest
import scala.meta.pc.ReferencesResult

import org.eclipse.{lsp4j => l}

class PcReferencesProvider(
    compiler: MetalsGlobal,
    request: ReferencesRequest,
    buffers: Buffers,
    scalaVersion: String
) extends WithCompilationUnit(compiler, request.params())
    with PcSymbolSearch {

  @volatile var isCancelled: Boolean = false
  def result(): List[ReferencesResult] = {

    val result: List[(String, l.Location)] =
      soughtSymbols match {
        case Some((sought, _)) if sought.nonEmpty =>
          def collect(adjustFileParams: PcAdjustFileParams) = {
            val collector =
              new WithCompilationUnit(compiler, adjustFileParams.params())
                with PcCollector[Option[(String, l.Range)]] {
                import compiler._
                override def collect(parent: Option[Tree])(
                    tree: Tree,
                    toAdjust: Position,
                    sym: Option[compiler.Symbol]
                ): Option[(String, l.Range)] = {
                  val (pos, _) = toAdjust.adjust(text)
                  tree match {
                    case _: DefTree if !request.includeDefinition() => None
                    case t =>
                      Some(compiler.semanticdbSymbol(t.symbol), pos.toLsp)
                  }
                }
              }

            val result =
              collector
                .resultWithSought(
                  sought.asInstanceOf[Set[collector.compiler.Symbol]]
                )
                .flatten
                .map { case (symbol, range) =>
                  (
                    symbol,
                    adjustFileParams.adjustLocation(
                      new l.Location(
                        adjustFileParams.params.uri().toString(),
                        range
                      )
                    )
                  )
                }
            compiler.unitOfFile.remove(collector.unit.source.file)
            result
          }

          if (sought.forall(_.isLocalToBlock)) {
            val file = buffers.getFile(params.uri(), scalaVersion)
            if (file.isPresent() && !isCancelled) {
              collect(file.get())
            } else Nil
          } else {
            val fileUris = request.targetUris().asScala.toList
            fileUris.flatMap { uri =>
              val file = buffers.getFile(uri, scalaVersion)
              if (file.isPresent()) collect(file.get())
              else Nil
            }
          }
        case _ => List.empty
      }

    result
      .groupBy(_._1)
      .map { case (symbol, locs) =>
        ReferencesResultImpl(symbol, locs.map(_._2).asJava)
      }
      .toList
  }
}
