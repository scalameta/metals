package scala.meta.internal.pc

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
    typedTree match {
      case Apply(fun, args) =>
        args
          .zipWithIndex
          .zip(fun.tpe.params)
          .collect {
            case ((arg, index), param) if argIndices.contains(index) => {
              val position = arg.pos.toLSP
              position.setEnd(position.getStart())
              new l.TextEdit(position, s"${param.nameString} = ")
            }
          }
      case _ => Nil
    }
  }
}
