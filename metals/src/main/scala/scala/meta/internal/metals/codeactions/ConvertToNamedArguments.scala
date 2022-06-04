package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ConvertToNamedArguments(trees: Trees)
    extends CodeAction {

  import ConvertToNamedArguments._
  override val kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    trees
      .findLastEnclosingAt[Term.Apply](path, range.getStart())
      .map { apply =>
        {
          // TODO: Skip block args
          // TODO: Go to parent apply, if current has no candidates
          pprint.log(apply)
          val argIndices = apply.args.zipWithIndex.filterNot { case (arg, _) => arg.isInstanceOf[Term.Assign]}.map(_._2)
          pprint.log(argIndices)
          //val numUnnamedArgs =
            //apply.args.takeWhile(!_.isInstanceOf[Term.Assign]).length
          if (argIndices.isEmpty) Future.successful(Nil)
          else {
            val codeAction = new l.CodeAction(title)
            codeAction.setKind(l.CodeActionKind.RefactorRewrite)
            val position = new l.TextDocumentPositionParams(
              params.getTextDocument(),
              new l.Position(apply.pos.endLine, apply.pos.endColumn)
            )
            codeAction.setCommand(
              ServerCommands.ConvertToNamedArguments.toLSP(
                ServerCommands.ConvertToNamedArgsRequest(position, argIndices.map(new Integer(_)).asJava)
              )
            )
            Future.successful(Seq(codeAction))
            
          }
        }
      }
      .getOrElse(Future.successful(Nil))
  }
}

object ConvertToNamedArguments {
  val title = "Convert to named arguments"
}
