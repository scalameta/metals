package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Enumerator
import scala.meta.Pat
import scala.meta.Term
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class InsertInferredType(
    trees: Trees,
    compilers: Compilers,
    languageClient: MetalsLanguageClient,
) extends CodeAction {

  type CommandData = l.TextDocumentPositionParams

  override def command: Option[ActionCommand] =
    Some(ServerCommands.InsertInferredType)

  import InsertInferredType._
  override def kind: String = l.CodeActionKind.QuickFix

  override val maybeCodeActionId: Option[String] = Some(
    "InsertInferredType"
  )

  override def handleCommand(
      textDocumentParams: l.TextDocumentPositionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val uri = textDocumentParams.getTextDocument().getUri()
    for {
      edits <- compilers.insertInferredType(
        textDocumentParams,
        token,
      )
      if (!edits.isEmpty())
      workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
      _ <- languageClient
        .applyEdit(new l.ApplyWorkspaceEditParams(workspaceEdit))
        .asScala
    } yield ()

  }

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
      val range = params.getRange().getStart()
      val commandTypeAction =
        ServerCommands.InsertInferredType.toLsp(
          new l.TextDocumentPositionParams(
            params.getTextDocument(),
            range,
          )
        )

      CodeActionBuilder.build(
        title = title,
        kind = l.CodeActionKind.RefactorRewrite,
        command = Some(commandTypeAction),
      )
    }

    def inferTypeTitle(name: Term.Name): Option[String] = name.parent.flatMap {
      case pattern @ Pat.Var(_) =>
        pattern.parent
          .collect {
            case Pat.Typed(_, _) => None
            case _: Pat.Bind => None
            case vl: Defn.Val if vl.decltpe.isEmpty => Some(insertType)
            case vr: Defn.Var if vr.decltpe.isEmpty => Some(insertType)
            case _: Pat | _: Enumerator | _: Pat.ArgClause =>
              Some(insertTypeToPattern)
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
        Some(name.pos.toLsp)
      case Defn.GivenAlias(_, name, _, _, _, _) =>
        Some(name.pos.toLsp)
      case Defn.Val(_, List(Pat.Var(name)), tpe, _) if tpe.isDefined =>
        Some(name.pos.toLsp)
      case Defn.Var(_, List(Pat.Var(name)), tpe, _) if tpe.isDefined =>
        Some(name.pos.toLsp)
      case _ =>
        None
    }

    def adjustTypeAction(typ: String, range: l.Range): l.CodeAction = {
      val commandTypeAction =
        ServerCommands.InsertInferredType.toLsp(
          new l.TextDocumentPositionParams(
            params.getTextDocument(),
            range.getStart(),
          )
        )

      CodeActionBuilder.build(
        title = InsertInferredType.adjustType(typ),
        kind = l.CodeActionKind.QuickFix,
        command = Some(commandTypeAction),
      )
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
