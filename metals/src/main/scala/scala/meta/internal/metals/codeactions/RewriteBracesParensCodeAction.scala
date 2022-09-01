package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.ClassTag

import scala.meta.Term
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

/**
 * Rewrite parens to brackets and vice versa.
 * It's a transformation between Term.Apply(_, List(_: Term)) and Term.Apply(_, List(Term.Block(List(_: Term))))
 * Term.Block is equivalent to "surrounded by braces"
 *
 * Parens to brackets scenarios:
 * 1: def foo(n: Int) = ???
 *    foo(5)           ->   foo{5}
 * 2: x.map(a => a)    ->   x.map{a => a}
 * 3: x.map(_ match {       x.map{_ match {
 *      case _ => 0    ->     case _ => 0
 *    })                    }}
 * Brackets to parens scenarios are the opposite of the ones above.
 */
class RewriteBracesParensCodeAction(
    trees: Trees
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val applyTree =
      if (range.getStart == range.getEnd)
        trees
          .findLastEnclosingAt[Term.Apply](
            path,
            range.getStart(),
            applyWithSingleFunction,
          )
      else None

    applyTree
      .map {
        // order matter because List(Term.Block(List(_: Term))) includes in  List(t: Term)
        case appl @ Term.Apply(_, List(Term.Block(List(_: Term)))) =>
          switchFrom[Token.LeftBrace, Token.RightBrace](path, appl)

        case appl @ Term.Apply(_, List(_: Term)) =>
          switchFrom[Token.LeftParen, Token.RightParen](path, appl)

        case _ =>
          Nil
      }
      .getOrElse(Nil)
  }

  private def switchFrom[L: ClassTag, R: ClassTag](
      path: AbsolutePath,
      appl: Term.Apply,
  ): Seq[l.CodeAction] = {
    val select = appl.fun
    val term = appl.args.head match {
      case Term.Block(List(t: Term)) => t
      case f => f
    }
    val tokens = appl.tokens
    tokens
      .collectFirst {
        case leftParen: L if leftParen.pos.start >= select.pos.end =>
          tokens.collectFirst {
            case rightParen: R if rightParen.pos.start >= term.pos.end =>
              val isParens = leftParen.text == "("
              val newLeft = if (isParens) "{" else "("
              val newRight = if (isParens) "}" else ")"
              val start = new l.TextEdit(leftParen.pos.toLsp, newLeft)
              val end = new l.TextEdit(rightParen.pos.toLsp, newRight)

              val name = appl.fun match {
                case Term.Name(value) => value
                case Term.Select(_, Term.Name(value)) => value
                case _ => ""
              }

              val title =
                if (isParens) RewriteBracesParensCodeAction.toBraces(name)
                else RewriteBracesParensCodeAction.toParens(name)

              CodeActionBuilder.build(
                title = title,
                kind = this.kind,
                changes = List(path -> List(start, end)),
              )
          }
      }
      .flatten
      .toSeq
  }

  private def applyWithSingleFunction: Term.Apply => Boolean = {
    // exclude case when body has more than one line (is a Block) because it cannot be rewritten to parens
    case Term.Apply(
          _,
          List(Term.Block(List(Term.Function(_, _: Term.Block)))),
        ) =>
      false
    case Term.Apply(_, List(_: Term)) => true
    //   Term.Apply(_, List(Term.Block(List(_: Term)))) is already included in the one above
    case _ => false
  }
}

object RewriteBracesParensCodeAction {
  def toParens(name: String): String =
    s"Rewrite ${addSpaceSuffixIfNonempty(name)}to parenthesis"
  def toBraces(name: String): String =
    s"Rewrite ${addSpaceSuffixIfNonempty(name)}to braces"

  private def addSpaceSuffixIfNonempty(str: String): String =
    if (str.nonEmpty) s"$str "
    else str
}
