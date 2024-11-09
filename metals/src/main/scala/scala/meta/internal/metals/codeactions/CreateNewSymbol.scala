package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.ScalacDiagnostic
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class CreateNewSymbol(
    compilers: Compilers,
    buildTargets: BuildTargets,
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
      workspaceEdit <- compilers.insertInferredMethod(
        textDocumentParams,
        token,
      )
      if (!workspaceEdit.getChanges().isEmpty())
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
    lazy val isScala3 =
      (for {
        buildId <- buildTargets.inverseSources(file)
        target <- buildTargets.scalaTarget(buildId)
        isScala3 = ScalaVersions.isScala3Version(
          target.scalaVersion
        )
      } yield isScala3).getOrElse(false)

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

    val codeActions = params
      .getContext()
      .getDiagnostics()
      .asScala
      .groupBy {
        case ScalacDiagnostic.SymbolNotFound(name) if name.nonEmpty =>
          Some(name)
        case ScalacDiagnostic.NotAMember(name) if name.nonEmpty =>
          Some(name)
        case _ => None
      }
      .collect {
        case (Some(name), diags)
            if !isScala3 // scala 3 not supported yet
              && name.head.isLower && params
                .getRange()
                .overlapsWith(diags.head.getRange()) =>
          createNewMethod(diags.head, name)
        case (Some(name), diags)
            if params.getRange().overlapsWith(diags.head.getRange()) =>
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
