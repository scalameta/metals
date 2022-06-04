package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class ConvertToNamedArgumentsProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams,
    argIndices: Seq[Int]
) {

  private val argIndexSet = argIndices.toSet
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
        pprint.log(fun)
        args.zipWithIndex.filter { case (_, index) => argIndexSet.contains(index) }
          .map(_._1)
          .zip(fun.tpe.params)
        //args.take(numUnnamedArgs).zip(fun.tpe.params)
          .map {
          case (arg, param) => {
            val position = arg.pos.toLSP
            position.setEnd(position.getStart())
            new l.TextEdit(position, s"${param.nameString} = ")
          }
        }
      case _ => Nil
    }
  }
  object Something {
    def f(x: Seq[Int]) = x.map ( _.toLong )
  }
}
