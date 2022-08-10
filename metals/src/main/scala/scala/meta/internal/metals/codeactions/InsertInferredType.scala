package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Enumerator
import scala.meta.Pat
import scala.meta.Term
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class InsertInferredType(trees: Trees) extends CodeAction {

  import InsertInferredType._
  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {

    def typeMismatch: Option[(String, l.Diagnostic)] = {
      params
        .getContext()
        .getDiagnostics()
        .asScala
        .collectFirst { case ScalacDiagnostic.TypeMismatch(typ, diag) =>
          (typ, diag)
        }
    }

    def insertInferTypeAction(title: String): l.CodeAction = {
      val codeAction = new l.CodeAction()
      codeAction.setTitle(title)
      codeAction.setKind(l.CodeActionKind.RefactorRewrite)
      val range = params.getRange().getStart()
      codeAction.setCommand(
        ServerCommands.InsertInferredType.toLSP(
          new l.TextDocumentPositionParams(
            params.getTextDocument(),
            range,
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

    val path = params.getTextDocument().getUri().toAbsolutePath
    val actions = for {
      name <- trees
        .findLastEnclosingAt[Term.Name](path, params.getRange().getStart())
      title <- inferTypeTitle(name)
    } yield insertInferTypeAction(title)

    def typedDefnTreePos(tree: Defn): Option[l.Range] = tree match {
      case Defn.Def(_, name, _, _, tpe, _) if tpe.isDefined =>
        Some(name.pos.toLSP)
      case Defn.GivenAlias(_, name, _, _, _, _) =>
        Some(name.pos.toLSP)
      case Defn.Val(_, List(Pat.Var(name)), tpe, _) if tpe.isDefined =>
        Some(name.pos.toLSP)
      case Defn.Var(_, List(Pat.Var(name)), tpe, _) if tpe.isDefined =>
        Some(name.pos.toLSP)
      case _ =>
        None
    }

    def adjustTypeAction(typ: String, range: l.Range): l.CodeAction = {
      val codeAction = new l.CodeAction()
      codeAction.setTitle(InsertInferredType.adjustType(typ))
      codeAction.setKind(l.CodeActionKind.QuickFix)
      codeAction.setCommand(
        ServerCommands.InsertInferredType.toLSP(
          new l.TextDocumentPositionParams(
            params.getTextDocument(),
            range.getStart(),
          )
        )
      )
      codeAction
    }

    val adjustType = for {
      (mismatchedType, diag) <- typeMismatch
      defn <-
        trees.findLastEnclosingAt[Defn](path, diag.getRange().getStart())
      pos <- typedDefnTreePos(defn)
    } yield adjustTypeAction(mismatchedType, pos)

    List(actions, adjustType).flatten
  }
}

object InsertInferredType {
  val insertType = "Insert type annotation"
  def adjustType(typ: String): String = s"Adjust type to $typ"
  val insertTypeToPattern = "Insert type annotation into pattern definition"
}
