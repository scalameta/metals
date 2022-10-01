package scala.meta.internal.pc

import scala.meta.internal.mtags.KeywordWrapper
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class ConvertToNamedArgumentsProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams,
    argIndices: Set[Int]
) {

  import compiler._
  def convertToNamedArguments: List[l.TextEdit] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )

    val typedTree = typedTreeAt(unit.position(params.offset))
    object FromNewApply {
      def unapply(tree: Tree): Option[(Tree, List[Tree])] =
        tree match {
          case fun @ Select(New(_), _) =>
            Some((fun, Nil))
          case Apply(FromNewApply(fun, argss), args) =>
            Some(fun, argss ++ args)
          case _ => None
        }
    }

    def makeTextEdits(params: List[Symbol], args: List[Tree]) = {
      args.zipWithIndex.zip(params).collect {
        case ((arg, index), param) if argIndices.contains(index) => {
          val position = arg.pos.toLsp
          position.setEnd(position.getStart())
          val paramNameText =
            KeywordWrapper.Scala2.backtickWrap(param.nameString)

          new l.TextEdit(position, s"$paramNameText = ")
        }
      }
    }

    typedTree match {
      case FromNewApply(fun, args) =>
        makeTextEdits(fun.tpe.paramss.flatten, args)
      case Apply(fun, args) =>
        makeTextEdits(fun.tpe.params, args)
      case _ => Nil
    }
  }
}
