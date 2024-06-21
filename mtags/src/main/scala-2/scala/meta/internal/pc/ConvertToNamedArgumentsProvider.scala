package scala.meta.internal.pc

import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.KeywordWrapper
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class ConvertToNamedArgumentsProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams,
    argIndices: Set[Int]
) {

  import compiler._

  val utils = new ConvertToNamedArgumentsUtils(params.text())
  def convertToNamedArguments: Either[String, List[l.TextEdit]] = {
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

    def makeTextEdits(
        params: List[Symbol],
        args: List[Tree],
        argsStart: Int,
        argsEnd: Int
    ) = {
      var prevArgEnd: Int = argsStart
      val edits: ListBuffer[l.TextEdit] = new ListBuffer()
      for (((arg, index), param) <- args.zipWithIndex.zip(params)) {
        if (argIndices.contains(index)) {
          val start = utils.findActualArgBeginning(prevArgEnd, arg.pos.start)
          val position = arg.pos.withStart(start).withEnd(start).toLsp
          val paramNameText =
            KeywordWrapper.Scala2.backtickWrap(param.nameString)
          edits += new l.TextEdit(position, s"$paramNameText = ")
        }
        prevArgEnd = utils.findActualArgEnd(arg.pos.end, argsEnd)
      }
      edits.result()
    }

    def handleWithJavaFilter(symbol: Symbol, edits: => List[l.TextEdit]) = {
      if (symbol.isJava)
        Left(CodeActionErrorMessages.ConvertToNamedArguments.IsJavaObject)
      else Right(edits)
    }

    typedTree match {
      case app @ FromNewApply(fun, args) if fun.symbol != null =>
        handleWithJavaFilter(
          fun.symbol,
          makeTextEdits(
            fun.tpe.paramss.flatten,
            args,
            fun.pos.end,
            app.pos.end - 1
          )
        )
      case app @ Apply(fun, args) if fun.symbol != null && !fun.symbol.isJava =>
        handleWithJavaFilter(
          fun.symbol,
          makeTextEdits(fun.tpe.params, args, fun.pos.end, app.pos.end - 1)
        )
      // class A extends <<B(1)>>
      // this is a workaround case, since the position seems to off by one here
      case Template(
            _,
            _,
            List(DefDef(_, _, _, _, _, Block(List(app @ Apply(fun, args)), _)))
          )
          if fun.symbol != null && !fun.symbol.isJava && app.pos.includes(
            unit.position(params.offset - 1)
          ) =>
        handleWithJavaFilter(
          fun.symbol,
          makeTextEdits(
            fun.tpe.params,
            args,
            fun.pos.end + 1,
            app.pos.end
          )
        )
      case _ => Right(Nil)
    }
  }
}
