package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.Tree
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

  def firstApplyWithUnnamedArgs(term: Option[Tree]): Option[ApplyTermWithNumArgs] = {
    term match {
      case Some(apply: Term.Apply) => 
        val numUnnamedArgs = apply.args.takeWhile{ arg => 
          !arg.isInstanceOf[Term.Assign] && !arg.isInstanceOf[Term.Block]
        }.size
        if (numUnnamedArgs == 0) firstApplyWithUnnamedArgs(apply.parent)
        else Some(ApplyTermWithNumArgs(apply, numUnnamedArgs))
      case Some(t) => firstApplyWithUnnamedArgs(t.parent)
      case _ => None
    }
  }

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val maybeApply = for {
      term <- trees.findLastEnclosingAt[Term.Apply](path, range.getStart())
      apply <- firstApplyWithUnnamedArgs(Some(term))
    } yield apply

    maybeApply.map { apply => {
      val codeAction = new l.CodeAction(title)
      codeAction.setKind(l.CodeActionKind.RefactorRewrite)
      val position = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        new l.Position(apply.term.pos.endLine, apply.term.pos.endColumn)
      )
      codeAction.setCommand(
        ServerCommands.ConvertToNamedArguments.toLSP(
          ServerCommands.ConvertToNamedArgsRequest(position, apply.numUnnamedArgs)
        )
      )
      Future.successful(Seq(codeAction))
    }}.getOrElse(Future.successful(Nil))
  }
}

object ConvertToNamedArguments {
  case class ApplyTermWithNumArgs(term: Term.Apply, numUnnamedArgs: Int)
  val title = "Convert to named arguments"
}
