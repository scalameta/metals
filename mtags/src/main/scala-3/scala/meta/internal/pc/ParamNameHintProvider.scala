package scala.meta.internal.pc

import scala.meta.io.AbsolutePath
import dotty.tools.dotc.interactive.InteractiveDriver
import scala.meta.pc.PresentationCompilerConfig
import java.nio.file.Paths
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.core.Flags
import meta.internal.mtags.MtagsEnrichments.*
import dotty.tools.dotc.interactive.Interactive
import scala.meta.internal.pc.IndexedContext
import scala.meta.internal.pc.MetalsInteractive
import dotty.tools.dotc.ast.Trees.Apply
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import scala.meta.pc.ParamNameHintResult
import scala.meta.pc.VirtualFileParams

final class ParamNameHintProvider(
    params: VirtualFileParams,
    driver: InteractiveDriver,
    config: PresentationCompilerConfig
):

  def paramNameHints: List[ParamNameHintResult] =
    val uri = params.uri
    val filePath = Paths.get(uri)
    driver.run(
      uri,
      SourceFile.virtual(filePath.toString, params.text)
    )
    val unit = driver.currentCtx.run.units.head
    val tree = unit.tpdTree
    val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)

    def collectArgss(a: tpd.Apply): List[List[tpd.Tree]] =
      a.fun match
        case app: tpd.Apply => collectArgss(app) :+ a.args
        case _ => List(a.args)

    val result = collection.mutable.ListBuffer.empty[ParamNameHintResult]
    val traverser = new tpd.TreeTraverser:
      def traverse(tree: tpd.Tree)(using Context) = tree match
        case app: tpd.Apply if app.span.exists =>
          val argss = collectArgss(app)
          if argss.flatten.filter(_.span.isSourceDerived).length < 3 then ()
          else
            val paramss = app.fun.symbol.rawParamss
            val pairs = argss
              .zip(paramss)
              .flatMap { (args, params) =>
                args.zip(params)
              }
              .foreach { (arg, param) =>
                if arg.span.isSourceDerived then
                  result.addOne(
                    ParamNameHintResultImpl(
                      arg.sourcePos.toLSP,
                      s"${param.name.show} = "
                    )
                  )
              }
          end if
        case t =>
          traverseChildren(t)
    traverser.traverse(tree)(using newctx)
    result.result
  end paramNameHints
end ParamNameHintProvider
