package scala.meta.internal.metals.codeactions

import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range
import org.eclipse.{lsp4j => l}

/**
 * Code action that provides detailed explanation for Scala 3 diagnostics
 * that have the "longer explanation available when compiling with `-explain`" hint.
 *
 * This code action is used if no virtual documents are supported, since it writes
 * to a temporary file in the workspace.
 */
class ExplainDiagnostic(
    compilers: Compilers,
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext)
    extends CodeAction {
  override def kind: String = l.CodeActionKind.QuickFix

  override type CommandData = l.TextDocumentPositionParams
  override def command: Option[ActionCommand] = Some(
    ServerCommands.ExplainDiagnostic
  )

  override def handleCommand(data: CommandData, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    explain(data)
  }

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    val isExplainDiagnosticSupported = buildTargets
      .inverseSources(params.getTextDocument().getUri().toAbsolutePath)
      .flatMap(buildTargets.scalaTarget)
      .exists(target => target.supportExplainDiagnostic)

    if (
      !clientConfig.isVirtualDocumentSupported() && isExplainDiagnosticSupported
    ) {
      val diagnosticsWithExplain = params
        .getContext()
        .getDiagnostics()
        .asScala
        .filter(d => params.getRange().overlapsWith(d.getRange()))

      Future.successful(
        diagnosticsWithExplain.map { diagnostic =>
          val command = ServerCommands.ExplainDiagnostic.toLsp(
            new l.TextDocumentPositionParams(
              params.getTextDocument(),
              diagnostic.getRange().getStart(),
            )
          )

          CodeActionBuilder.build(
            title = ExplainDiagnostic.title,
            kind = l.CodeActionKind.QuickFix,
            command = Some(command),
            diagnostics = List(diagnostic),
          )
        }.toSeq
      )
    } else {
      Future.successful(Nil)
    }
  }

  private def explain(params: l.TextDocumentPositionParams)(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    val path = params.getTextDocument().getUri().toAbsolutePath
    for {
      explainedDiagnostics <- compileWithExplain(path)
      outputPath <- writeExplainedDiagnostics(
        path,
        explainedDiagnostics,
        params,
      )
      _ <- showFileToUser(outputPath)
    } yield ()
  }

  private def compileWithExplain(
      path: AbsolutePath
  ): Future[List[Diagnostic]] = {
    compilers.compileWithExplain(path)
  }

  private def findMatchingDiagnostic(
      diagnostics: List[Diagnostic],
      params: l.TextDocumentPositionParams,
  ): Option[Diagnostic] = {
    diagnostics.find { diag =>
      val range = diag.getRange()
      range.getStart().getLine() == params.getPosition.getLine &&
      range.getStart().getCharacter() == params.getPosition.getCharacter
    }
  }

  private def writeExplainedDiagnostics(
      sourcePath: AbsolutePath,
      diagnostics: List[Diagnostic],
      params: l.TextDocumentPositionParams,
  ): Future[AbsolutePath] = Future {
    val outputDir = workspace.resolve(Directories.explainedDiagnostics)
    Files.createDirectories(outputDir.toNIO)

    val timestamp = LocalDateTime
      .now()
      .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
    val filename =
      s"${sourcePath.filename}-L${params.getPosition.getLine() + 1}-$timestamp.md"
    val outputPath = outputDir.resolve(filename)

    val matchingDiag = findMatchingDiagnostic(diagnostics, params)
    val relativePath = sourcePath.toRelative(workspace)

    val content = Messages.ExplainDiagnostic.content(
      matchingDiag,
      diagnostics,
      params.getPosition.getLine(),
      params.getPosition.getCharacter(),
      relativePath,
    )

    outputPath.writeText(content)
    outputPath
  }

  private def showFileToUser(path: AbsolutePath): Future[Unit] = Future {
    val startPosition = new l.Position(0, 0)
    val range = new Range(startPosition, startPosition)

    languageClient.metalsExecuteClientCommand(
      ClientCommands.GotoLocation.toExecuteCommandParams(
        ClientCommands.WindowLocation(
          path.toURI.toString,
          range,
        )
      )
    )
  }

}

object ExplainDiagnostic {
  val title: String = "Explain with -explain"
}
