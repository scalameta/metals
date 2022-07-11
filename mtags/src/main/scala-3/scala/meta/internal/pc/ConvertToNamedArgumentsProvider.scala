package scala.meta.internal.pc

import java.nio.file.Paths

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import org.eclipse.{lsp4j as l}

final class ConvertToNamedArgumentsProvider(
    driver: InteractiveDriver,
    params: OffsetParams,
    argIndices: Set[Int]
):

  def convertToNamedArguments: List[l.TextEdit] =
    val uri = params.uri
    val filePath = Paths.get(uri)
    driver.run(
      uri,
      SourceFile.virtual(filePath.toString, params.text)
    )
    val unit = driver.currentCtx.run.units.head
    val tree = unit.tpdTree
    val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
    val result = collection.mutable.ListBuffer.empty[l.TextEdit]
    val traverser = new tpd.TreeTraverser:
      def traverse(tree: tpd.Tree)(using Context) =
        tree match
          case tpd.Apply(fun, args) =>
            args.zipWithIndex
              .zip(fun.symbol.rawParamss.flatten)
              .collect {
                case ((arg, index), param) if argIndices.contains(index) => {
                  val position = arg.sourcePos.toLSP
                  position.setEnd(position.getStart())
                  new l.TextEdit(position, s"${param.name.show} = ")
                }
              }
              .foreach(result.addOne(_))
          case t =>
            traverseChildren(t)
    traverser.traverse(tree)(using newctx)
    result.result
  end convertToNamedArguments
end ConvertToNamedArgumentsProvider
