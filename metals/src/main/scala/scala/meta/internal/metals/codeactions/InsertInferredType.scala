package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Enumerator
import scala.meta.Pat
import scala.meta.Term
import scala.meta.Tree
import scala.meta.dialects
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.parsing.Trees
import scala.meta.internal.trees.Origin
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class InsertInferredType(trees: Trees, compilers: Compilers)
    extends CodeAction {

  import InsertInferredType._
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {

    def insertInferTypeAction(title: String): l.CodeAction = {
      val codeAction = new l.CodeAction()
      codeAction.setTitle(title)
      codeAction.setKind(l.CodeActionKind.RefactorRewrite)
      val range = params.getRange().getStart()
      codeAction.setCommand(
        ServerCommands.InsertInferredType.toLSP(
          List(
            params.getTextDocument().getUri(),
            range.getLine(): java.lang.Integer,
            range.getCharacter(): java.lang.Integer
          )
        )
      )
      codeAction
    }

    def inferTypeTitle(name: Term.Name): Option[String] = name.parent.flatMap {
      case pattern @ Pat.Var(_) =>
        pattern.parent
          .collect {
            case Pat.Typed(_, _) => None
            case Pat.Bind(lhs, _) if lhs != pattern => Some(insertTypeToPattern)
            case _: Pat.Bind => None
            case vl: Defn.Val if vl.decltpe.isEmpty => Some(insertType)
            case vr: Defn.Var if vr.decltpe.isEmpty => Some(insertType)
            case _: Pat | _: Enumerator => Some(insertTypeToPattern)
          }
          .getOrElse(None)
      case Defn.Def(_, _, _, _, tpe, _) if tpe.isEmpty =>
        Some(insertType)
      case Term.Param(_, _, tpe, _) if tpe.isEmpty =>
        Some(insertType)
      case _ =>
        None
    }

    def isScala2(tree: Tree) = {
      tree.origin match {
        case Origin.None => false
        case Origin.Parsed(_, dialect, _) => dialect != dialects.Scala3
      }
    }

    val path = params.getTextDocument().getUri().toAbsolutePath
    val actions = for {
      name <- trees
        .findLastEnclosingAt[Term.Name](path, params.getRange().getStart())
      if isScala2(name)
      title <- inferTypeTitle(name)
    } yield insertInferTypeAction(title)

    actions.toList
  }
}

object InsertInferredType {
  val insertType = "Insert type annotation"
  val insertTypeToPattern = "Insert type annotation into pattern definition"
}
