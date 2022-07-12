package scala.meta.internal.pc

import java.nio.file.Paths

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

    def paramss(fun: tpd.Tree)(using Context): List[List[String]] =
      fun.tpe match
        case m: MethodType => m.paramNamess.map(_.map(_.toString))
        case _ => fun.symbol.rawParamss.map(_.map(_.name.show))
    def edits(tree: Option[tpd.Tree])(using Context): List[l.TextEdit] =
      tree match
        case Some(t) =>
          t match
            case tpd.Apply(fun, args) =>
              args.zipWithIndex
                .zip(paramss(fun).flatten)
                .collect {
                  case ((arg, index), param) if argIndices.contains(index) => {
                    val position = arg.sourcePos.toLSP
                    position.setEnd(position.getStart())
                    new l.TextEdit(position, s"$param = ")
                  }
                }
            case _ => Nil
        case _ => Nil
    edits(tree)(using newctx)
  end convertToNamedArguments
end ConvertToNamedArgumentsProvider
