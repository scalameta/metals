package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.logging
import scala.meta.pc.CancelToken
import scala.meta.pc.CodeActionId

import org.eclipse.{lsp4j => l}

class CreateNewSymbol(
    compilers: Compilers,
    languageClient: MetalsLanguageClient,
) extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override type CommandData = l.TextDocumentPositionParams
  override def command: Option[ActionCommand] = Some(
    ServerCommands.InsertInferredMethod
  )

  override def handleCommand(
      textDocumentParams: l.TextDocumentPositionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      edits <- compilers.codeAction(
        textDocumentParams,
        token,
        CodeActionId.InsertInferredMethod,
        None,
      )
      _ = logging.logErrorWhen(
        edits.isEmpty(),
        s"Could not infer method at ${textDocumentParams}",
      )
      workspaceEdit = new l.WorkspaceEdit(
        Map(textDocumentParams.getTextDocument().getUri() -> edits).asJava
      )
      _ <- languageClient
        .applyEdit(new l.ApplyWorkspaceEditParams(workspaceEdit))
        .asScala
    } yield ()
  }

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    lazy val parentUri =
      params.getTextDocument.getUri.toAbsolutePath.parent.toURI

    val uri = params.getTextDocument().getUri()
    val file = uri.toAbsolutePath
    lazy val isSupportedInferredType = compilers
      .supportedCodeActions(file)
      .contains(CodeActionId.InsertInferredMethod)

    def createNewSymbol(
        diagnostic: l.Diagnostic,
        name: String,
    ): l.CodeAction = {
      val command =
        ServerCommands.NewScalaFile.toLsp(parentUri.toString(), name)

      CodeActionBuilder.build(
        title = CreateNewSymbol.title(name),
        kind = l.CodeActionKind.QuickFix,
        command = Some(command),
        diagnostics = List(diagnostic),
      )
    }

    def createNewMethod(
        diagnostic: l.Diagnostic,
        name: String,
    ): l.CodeAction = {
      val command =
        ServerCommands.InsertInferredMethod.toLsp(
          new l.TextDocumentPositionParams(
            params.getTextDocument(),
            params.getRange().getStart(),
          )
        )

      CodeActionBuilder.build(
        title = CreateNewSymbol.method(name),
        kind = l.CodeActionKind.QuickFix,
        command = Some(command),
        diagnostics = List(diagnostic),
      )
    }

    def overalaps(diags: Seq[l.Diagnostic]) = {
      params.getRange().overlapsWith(diags.head.getRange())
    }

    val codeActions = params
      .getContext()
      .getDiagnostics()
      .asScala
      .toSeq
      .groupBy {
        case ScalacDiagnostic.SymbolNotFound(name) if name.nonEmpty =>
          Some(name)
        case ScalacDiagnostic.NotAMember(name)
            if name.nonEmpty && isSupportedInferredType =>
          Some(name)
        case _ => None
      }
      .collect {
        case (Some(name), diags)
            if overalaps(diags) && name.head.isLower &&
              isSupportedInferredType =>
          createNewMethod(diags.head, name)
        case (Some(name), diags) if overalaps(diags) =>
          createNewSymbol(diags.head, name)
      }
      .toSeq
      .sorted

    Future.successful(codeActions)
  }
}

object CreateNewSymbol {
  def title(name: String): String = s"Create new symbol '$name'..."
  def method(name: String): String = s"Create new method 'def $name'..."
}
