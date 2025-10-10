package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.*

import scala.meta.Term
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class RemoveInfixRefactor(trees: Trees) extends CodeAction {

  override val kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val codeAction = trees
      .findLastEnclosingAt[Term.ApplyInfix](
        path,
        range.getStart(),
      )
      .map {
        case func @ Term.ApplyInfix(lhs, op, _, argClause) =>
          val select: Term = Term.Select(lhs, op)
          val apply = Term.Apply(select, argClause)
          val edits = List(new l.TextEdit(func.pos.toLsp, apply.syntax))
          val codeAction =
            CodeActionBuilder.build(
              title = RemoveInfixRefactor.title,
              kind = this.kind,
              changes = List(path -> edits),
            )

          Seq(codeAction)
        case _ => Nil
      }
      .getOrElse(Nil)

    Future(codeAction)
  }

}

object RemoveInfixRefactor {
  final val title: String =
    "Convert infix invocation into normal apply"
}
