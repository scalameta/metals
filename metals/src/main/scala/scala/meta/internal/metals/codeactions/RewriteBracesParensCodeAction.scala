package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.ClassTag

import scala.meta.Term
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class RewriteBracesParensCodeAction(
    trees: Trees
) extends CodeAction {

  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val applyWithSingleFunction: Term.Apply => Boolean = {
      case Term.Apply(_, List(_: Term.Function)) => true
      case Term.Apply(_, List(Term.Block(List(_: Term.Function)))) => true
      case _ => false
    }
    trees
      .findLastEnclosingAt[Term.Apply](
        path,
        range.getStart(),
        applyWithSingleFunction
      )
      .map {

        case appl @ Term.Apply(_, List(_: Term.Function)) =>
          switchTo[Token.LeftParen, Token.RightParen](path, appl)
        case appl @ Term.Apply(_, List(Term.Block(List(func: Term.Function))))
            if !func.body.isInstanceOf[Term.Block] =>
          switchTo[Token.LeftBrace, Token.RightBrace](path, appl)
        case _ =>
          Nil
      }
      .getOrElse(Nil)
  }

  private def switchTo[L: ClassTag, R: ClassTag](
      path: AbsolutePath,
      appl: Term.Apply
  ): Seq[l.CodeAction] = {
    val select = appl.fun
    val func = appl.args.head match {
      case Term.Block(List(f: Term.Function)) => f
      case f => f
    }
    val tokens = appl.tokens
    tokens
      .collectFirst {
        case leftParen: L if leftParen.pos.start >= select.pos.end =>
          tokens.collectFirst {
            case rightParen: R if rightParen.pos.start >= func.pos.end =>
              val isParens = leftParen.text == "("
              val newLeft = if (isParens) "{" else "("
              val newRight = if (isParens) "}" else ")"
              val start = new l.TextEdit(leftParen.pos.toLSP, newLeft)
              val end = new l.TextEdit(rightParen.pos.toLSP, newRight)
              val codeAction = new l.CodeAction()
              if (isParens)
                codeAction.setTitle(RewriteBracesParensCodeAction.toBraces)
              else
                codeAction.setTitle(RewriteBracesParensCodeAction.toParens)
              codeAction.setKind(this.kind)
              codeAction.setEdit(
                new l.WorkspaceEdit(
                  Map(path.toURI.toString -> List(start, end).asJava).asJava
                )
              )
              codeAction
          }
      }
      .flatten
      .toSeq
  }
}

object RewriteBracesParensCodeAction {
  val toParens = "Rewrite to parenthesis"
  val toBraces = "Rewrite to braces"
}
