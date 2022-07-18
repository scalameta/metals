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

class ConvertToNamedArguments(trees: Trees) extends CodeAction {

  import ConvertToNamedArguments._
  override val kind: String = l.CodeActionKind.RefactorRewrite

  def firstApplyWithUnnamedArgs(
      term: Option[Tree]
  ): Option[ApplyTermWithArgIndices] = {
    term match {
      case Some(apply: Term.Apply) =>
        val argIndices = apply.args.zipWithIndex.collect {
          case (arg, index)
              if !arg.isInstanceOf[Term.Assign] && !arg
                .isInstanceOf[Term.Block] =>
            index
        }
        if (argIndices.isEmpty) firstApplyWithUnnamedArgs(apply.parent)
        else Some(ApplyTermWithArgIndices(apply, argIndices))
      case Some(t) => firstApplyWithUnnamedArgs(t.parent)
      case _ => None
    }
  }

  private def methodName(t: Term, isFirst: Boolean = false): String = {
    t match {
      // a.foo(a)
      case Term.Select(_, name) =>
        name.value
      // foo(a)(b@@)
      case Term.Apply(fun, _) if isFirst =>
        methodName(fun) + "(...)"
      // foo(a@@)(b)
      case appl: Term.Apply =>
        methodName(appl.fun) + "()"
      // foo(a)
      case Term.Name(name) =>
        name
      case _ =>
        t.syntax
    }
  }
  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val maybeApply = for {
      term <- trees.findLastEnclosingAt[Term.Apply](
        path,
        range.getStart(),
        term => !term.fun.pos.encloses(range),
      )
      apply <- firstApplyWithUnnamedArgs(Some(term))
    } yield apply

    maybeApply
      .map { apply =>
        {
          val codeAction =
            new l.CodeAction(title(methodName(apply.app, isFirst = true)))
          codeAction.setKind(l.CodeActionKind.RefactorRewrite)
          val position = new l.TextDocumentPositionParams(
            params.getTextDocument(),
            new l.Position(apply.app.pos.endLine, apply.app.pos.endColumn),
          )
          codeAction.setCommand(
            ServerCommands.ConvertToNamedArguments.toLSP(
              ServerCommands
                .ConvertToNamedArgsRequest(
                  position,
                  apply.argIndices.map(new Integer(_)).asJava,
                )
            )
          )
          Future.successful(Seq(codeAction))
        }
      }
      .getOrElse(Future.successful(Nil))

  }
}

object ConvertToNamedArguments {
  case class ApplyTermWithArgIndices(app: Term.Apply, argIndices: List[Int])
  def title(funcName: String): String =
    s"Convert '$funcName' to named arguments"
}
