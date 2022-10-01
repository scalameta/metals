package scala.meta.internal.pc

import java.nio.file.Paths

import scala.meta.internal.mtags.KeywordWrapper
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import org.eclipse.{lsp4j as l}

final class ConvertToNamedArgumentsProvider(
    driver: InteractiveDriver,
    params: OffsetParams,
    argIndices: Set[Int],
):

  def convertToNamedArguments: List[l.TextEdit] =
    val uri = params.uri
    val filePath = Paths.get(uri)
    driver.run(
      uri,
      SourceFile.virtual(filePath.toString, params.text),
    )
    val unit = driver.currentCtx.run.units.head
    val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
    val pos = driver.sourcePosition(params)
    val trees = driver.openedTrees(uri)
    val tree = Interactive.pathTo(trees, pos)(using newctx).headOption
    val result = collection.mutable.ListBuffer.empty[l.TextEdit]

    def paramss(fun: tpd.Tree)(using Context): List[String] =
      fun.tpe match
        case m: MethodType => m.paramNamess.flatten.map(_.toString)
        case _ =>
          fun.symbol.rawParamss.flatten
            .filter(!_.isTypeParam)
            .map(_.nameBackticked)

    object FromNewApply:
      def unapply(tree: tpd.Tree): Option[(tpd.Tree, List[tpd.Tree])] =
        tree match
          case fun @ tpd.Select(tpd.New(_), _) =>
            Some((fun, Nil))
          case tpd.TypeApply(FromNewApply(fun, argss), _) =>
            Some(fun, argss)
          case tpd.Apply(FromNewApply(fun, argss), args) =>
            Some(fun, argss ++ args)
          case _ => None

    def edits(tree: Option[tpd.Tree])(using Context): List[l.TextEdit] =
      def makeTextEdits(fun: tpd.Tree, args: List[tpd.Tree]) =
        args.zipWithIndex
          .zip(paramss(fun))
          .collect {
            case ((arg, index), param) if argIndices.contains(index) => {
              val position = arg.sourcePos.toLsp
              position.setEnd(position.getStart())
              new l.TextEdit(position, s"$param = ")
            }
          }

      tree match
        case Some(t) =>
          t match
            case FromNewApply(fun, args) =>
              makeTextEdits(fun, args)
            case tpd.Apply(fun, args) =>
              makeTextEdits(fun, args)
            case _ => Nil
        case _ => Nil
      end match
    end edits
    edits(tree)(using newctx)
  end convertToNamedArguments
end ConvertToNamedArgumentsProvider
