package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.Pat
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}
import scala.meta.Defn

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

    val scopeOptions = toExtract
      .map(
        enclosingList(_).zipWithIndex.flatMap(e => enclosingDef(e._1, e._2))
      )

    val edits = toExtract.zip(scopeOptions)
    edits
      .map { case (apply: Term, scopes: List[(Tree, Int)]) =>
        if (correctPath(scopes)) {
          val applRange = apply.pos.end - apply.pos.start
          scopes.map { case (defn, lv) =>
            val scopeName = defnTitle(defn)
            val codeAction = new l.CodeAction(
              ExtractMethodCodeAction.title(apply.toString(), scopeName)
            )
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
                  new Integer(lv),
                )
              )
            )
            codeAction
          }
        } else Nil
      }
      .getOrElse(Nil)

  }

  private def defnTitle(defn: Tree): String = {
    defn match {
      case vl: Defn.Val =>
        vl.pats.head match {
          case Pat.Var(name) => s"val `${name}`"
          case _ => "val"
        }
      case vr: Defn.Var =>
        vr.pats.head match {
          case Pat.Var(name) => s"var `${name}`"
          case _ => "var"
        }
      case df: Defn.Def => s"method `${df.name}`"
      case cl: Defn.Class => s"class `${cl.name}`"
      case en: Defn.Enum => s"enum ${en.name}`"
      case ob: Defn.Object => s"object `${ob.name}`"
      case _ => "block"
    }
  }
  private def enclosingList(
      apply: Term
  ): (List[Tree]) = {

    def loop(tree: Tree): List[Tree] = {
      tree.parent match {
        case Some(t: Template) => t :: loop(t)
        case Some(b: Term.Block) => b :: loop(b)
        case Some(other) => loop(other)
        case None => Nil
      }
    }
    loop(apply)
  }

  private def enclosingDef(
      apply: Tree,
      lv: Int,
  ): Option[(Tree, Int)] = {
    apply.parent match {
      case Some(d: Defn) => Some(d, lv)
      case _ => None
    }
  }
  // We have to check if there are no val (a,b) = {...} on path
  private def correctPath(ts: List[(Tree, Int)]) = {
    !ts.exists(t =>
      t._1 match {
        case vl: Defn.Val =>
          vl.pats.head match {
            case _: Pat.Var => false
            case _ => true
          }
        case vr: Defn.Var =>
          vr.pats.head match {
            case _: Pat.Var => false
            case _ => true
          }
        case _ => false
      }
    )
  }
}

object ExtractMethodCodeAction {
  def title(expr: String, scopeName: String): String = {
    if (expr.length <= 10) s"Extract `$expr` as method in $scopeName"
    else s"Extract `${expr.take(10)}` ... as method in $scopeName"
  }
}
