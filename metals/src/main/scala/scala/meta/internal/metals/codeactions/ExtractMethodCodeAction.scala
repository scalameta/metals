package scala.meta.internal.metals.codeactions

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Case
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class ExtractMethodCodeAction(
    trees: Trees
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorExtract
  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val toExtract: Option[Term] = {
      val tree: Option[Tree] = trees.get(path)
      def loop(appl: Tree): Option[Term] = {
        appl.children.find(_.pos.encloses(range)) match {
          case Some(child) =>
            loop(child)
          case None =>
            if (
              appl.is[Term.Apply] | appl.is[Term.ApplyInfix] | appl
                .is[Term.ApplyUnary]
            ) Some(appl.asInstanceOf[Term])
            else None
        }
      }
      tree.flatMap(loop(_))
    }

    val edits = for {
      apply <- toExtract
      stats <- lastEnclosingStatsList(apply)
    } yield {
      val applRange = apply.pos.end - apply.pos.start
      (apply, applRange)
    }

    edits
      .map { case (apply, applRange) =>
        val codeAction =
          new l.CodeAction(ExtractMethodCodeAction.title(apply.toString()))
        codeAction.setKind(l.CodeActionKind.RefactorExtract)
        val applPos = new l.TextDocumentPositionParams(
          params.getTextDocument(),
          new l.Position(apply.pos.startLine, apply.pos.startColumn),
        )
        codeAction.setCommand(
          ServerCommands.ExtractMethod.toLSP(
            ServerCommands.ExtractMethodParams(
              applPos,
              new Integer(applRange),
            )
          )
        )
        Seq(codeAction)
      }
      .getOrElse(Nil)

  }

  private def lastEnclosingStatsList(
      apply: Term
  ): Option[(List[Tree])] = {

    @tailrec
    def loop(tree: Tree): Option[List[Tree]] = {
      tree.parent match {
        case Some(t: Template) => Some(t.stats)
        case Some(b: Term.Block) => Some(b.stats)
        case Some(_: Case) => None
        case Some(other) => loop(other)
        case None => None
      }
    }
    loop(apply)
  }
}

object ExtractMethodCodeAction {
  def title(expr: String): String = {
    if (expr.length <= 10) s"Extract `$expr` as method"
    else s"Extract `${expr.take(10)}` ... as method"
  }
}
