package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Pat
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

    val toExtract: Option[List[Tree]] = {
      if (range.getStart() == range.getEnd()) {
        None
      } else {
        val tree: Option[Tree] = trees.get(path)
        def loop(appl: Tree): Option[Tree] = {
          appl.children.find(_.pos.encloses(range)) match {
            case Some(child) =>
              loop(child)
            case None =>
              Some(appl)
          }
        }
        val enclosing = tree.flatMap(loop(_))
        enclosing.map(_ match {
          case Term.Block(stats) =>
            stats.filter((s: Tree) => range.encloses(s.pos.toLSP))
          case Template(_, _, _, stats) =>
            stats.filter((s: Tree) => range.encloses(s.pos.toLSP))
          case ap if returnsValue(ap) => List(ap)
          case _ => Nil
        })
      }
    }

    val edits = {
      for {
        exprs <- toExtract
        last <- exprs.lastOption
        head <- exprs.headOption if returnsValue(last)
        scopeOptions = enclosingList(head).flatMap(enclosingDef(_))
      } yield {
        (scopeOptions, last, head)
      }
    }
    edits
      .map { case (scopes: List[(Tree, Tree)], apply: Tree, head: Tree) =>
        scopes.map { case (defn, block) =>
          val defnPos =
            stats(block).find(_.pos.end >= head.pos.end).getOrElse(defn)
          val scopeName = defnTitle(defn)

          val codeAction = new l.CodeAction(
            ExtractMethodCodeAction.title(scopeName)
          )
          codeAction.setKind(l.CodeActionKind.RefactorExtract)

          codeAction.setCommand(
            ServerCommands.ExtractMethod.toLSP(
              ServerCommands.ExtractMethodParams(
                params.getTextDocument(),
                new l.Range(
                  head.pos.toLSP.getStart(),
                  apply.pos.toLSP.getEnd(),
                ),
                defnPos.pos.toLSP.getStart(),
              )
            )
          )
          codeAction
        }
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

  private def returnsValue(t: Tree) = {
    t match {
      case _: Term.ApplyUnary => true
      case _: Term.Apply => true
      case _: Term.ApplyInfix => true
      case _: Term.Match => true
      case _: Term.If => true
      case _: Term.Throw => true
      case _: Term.Return => true
      case _ => false
    }
  }

  private def enclosingList(
      apply: Tree
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
  private def stats(t: Tree): List[Tree] = {
    t match {
      case t: Template => t.stats
      case b: Term.Block => b.stats
      case other => List(other)
    }
  }

  private def enclosingDef(
      apply: Tree
  ): Option[(Tree, Tree)] = {
    apply.parent match {
      case Some(d: Defn) => Some(d, apply)
      case _ => None
    }
  }
}

object ExtractMethodCodeAction {
  def title(scopeName: String): String = {
    s"Extract selection as method in $scopeName"
  }
}
