package scala.meta.languageserver

import scala.meta.languageserver.protocol.RequestService
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.CompletionOptions
import langserver.messages.ExecuteCommandOptions
import langserver.messages.InitializeParams
import langserver.messages.InitializeResult
import langserver.messages.ServerCapabilities
import langserver.messages.SignatureHelpOptions
import monix.eval.Task
import monix.execution.Scheduler
import org.langmeta.io.AbsolutePath

class InitializeService(
    effects: List[Effects],
    cwd: AbsolutePath
)(implicit s: Scheduler)
    extends RequestService[InitializeParams, InitializeResult]("initialize")
    with LazyLogging {
  override def handle(request: InitializeParams) = Task {
    logger.info(s"Initialized with $cwd, $request")
//    cancelEffects = effects.map(_.subscribe())
//    loadAllRelevantFilesInThisWorkspace()
    val capabilities = ServerCapabilities(
      completionProvider = Some(
        CompletionOptions(
          resolveProvider = false,
          triggerCharacters = "." :: Nil
        )
      ),
      signatureHelpProvider = Some(
        SignatureHelpOptions(
          triggerCharacters = "(" :: Nil
        )
      ),
      definitionProvider = true,
      referencesProvider = true,
      documentHighlightProvider = true,
      documentSymbolProvider = true,
      documentFormattingProvider = true,
      hoverProvider = true,
      executeCommandProvider =
        ExecuteCommandOptions(WorkspaceCommand.values.map(_.entryName)),
      workspaceSymbolProvider = true,
      renameProvider = true
    )
    Right(InitializeResult(capabilities))
  }
}
