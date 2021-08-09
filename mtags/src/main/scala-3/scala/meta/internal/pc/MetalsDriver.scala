package scala.meta.internal.pc

import java.net.URI

import scala.meta.internal.pc.MetalsDriver.TypecheckResult

import dotty.tools.dotc.Compiler
import dotty.tools.dotc.ScalacCommand
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Comments._
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Mode
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interfaces.Diagnostic
import dotty.tools.dotc.reporting.HideNonSensicalMessages
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.reporting.UniqueMessagePositions
import dotty.tools.dotc.transform.SetRootTree
import dotty.tools.dotc.typer.FrontEnd
import dotty.tools.dotc.typer.ImportInfo._
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans

class MetalsDriver(initCtx: Context, compiler: Compiler) {

  def run(uri: URI, source: String): TypecheckResult = {
    val sourceFile = CompilerInterfaces.toSource(uri, source)
    run(uri, sourceFile)
  }

  def run(uri: URI, source: SourceFile): TypecheckResult = {
    val reporter =
      new StoreReporter(null)
        with UniqueMessagePositions
        with HideNonSensicalMessages
    val runContext = initCtx.fresh.setReporter(reporter)
    val run = compiler.newRun(using runContext)
    val ctx = run.runContext.withRootImports
    run.compileSources(List(source))
    val unit =
      if ctx.run.units.nonEmpty then ctx.run.units.head
      else ctx.run.suspendedUnits.head
    TypecheckResult(
      ctx.fresh.setCompilationUnit(unit),
      unit.tpdTree,
      source,
      reporter.removeBufferedMessages(using ctx)
    )
  }

}

object MetalsDriver {

  def create(options: List[String]): MetalsDriver = {
    new MetalsDriver(
      initCtx(options),
      new FrontendCompiler
    )
  }

  private def initCtx(settings: List[String]): Context = {
    val fresh = (new ContextBase).initialCtx.fresh
    val rootCtx = fresh.addMode(Mode.ReadPositions).addMode(Mode.Interactive)
    rootCtx.setSetting(rootCtx.settings.YretainTrees, true)
    rootCtx.setSetting(rootCtx.settings.YdropComments, true)
    val summary = ScalacCommand.distill(settings.toArray, rootCtx.settings)(
      rootCtx.settingsState
    )(using rootCtx)
    rootCtx.setSettings(summary.sstate)
    rootCtx.setProperty(ContextDoc, new ContextDocstrings)
    rootCtx
  }

  case class TypecheckResult(
      context: Context,
      tree: tpd.Tree,
      source: SourceFile,
      diagnostic: List[Diagnostic]
  ) {

    def positionOf(offset: Int): SourcePosition = {
      val p = Spans.Span(offset)
      new SourcePosition(source, p)
    }

    def pathTo(offset: Int): List[tpd.Tree] =
      Interactive.pathTo(tree, Spans.Span(offset))(using context)
  }

  class FrontendCompiler extends Compiler {
    override def phases: List[List[Phase]] = List(
      List(new FrontEnd)
    )
  }
}
