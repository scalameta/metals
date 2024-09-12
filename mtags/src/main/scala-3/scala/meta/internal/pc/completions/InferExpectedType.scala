package scala.meta.internal.pc

import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.completions.InterCompletionType
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver

class InferExpectedType(
    search: SymbolSearch,
    driver: InteractiveDriver,
    params: OffsetParams
)(implicit rc: ReportContext):
  val uri = params.uri

  val sourceFile = CompilerInterfaces.toSource(params.uri, params.text())
  driver.run(uri, sourceFile)

  val ctx = driver.currentCtx
  val pos = driver.sourcePosition(params)

  def infer() = 
    driver.compilationUnits.get(uri) match
      case Some(unit) =>
        val path =
          Interactive.pathTo(driver.openedTrees(uri), pos)(using ctx)
        val newctx = ctx.fresh.setCompilationUnit(unit)
        val tpdPath =
          Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using
            newctx
          )
        val locatedCtx =
          Interactive.contextOfPath(tpdPath)(using newctx)
        val indexedCtx = IndexedContext(locatedCtx)
        val printer = MetalsPrinter.standard(
          indexedCtx,
          search,
          includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
        )
        InterCompletionType.inferType(path)(using newctx).map{
          tpe => printer.tpe(tpe)
        }
      case None => None
