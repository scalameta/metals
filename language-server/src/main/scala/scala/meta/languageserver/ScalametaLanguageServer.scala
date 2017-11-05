package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import scalafix.languageserver.ScalafixLintProvider
import langserver.core.LanguageServer
import langserver.messages.ClientCapabilities
import langserver.messages.FileChangeType
import langserver.messages.FileEvent
import langserver.messages.ServerCapabilities
import org.langmeta.io.AbsolutePath

class ScalametaLanguageServer(cwd: AbsolutePath,
                              in: InputStream,
                              out: OutputStream)
    extends LanguageServer(in, out) {
  val ps = new PrintStream(out)
  val scalafixService = new ScalafixLintProvider(cwd, out, connection)

  override def initialize(
      pid: Long,
      rootPath: String,
      capabilities: ClientCapabilities): ServerCapabilities = {
    logger.info(s"Initialized with $cwd, $pid, $rootPath, $capabilities")

    ServerCapabilities(completionProvider = None)
  }

  def report(result: DiagnosticsReport): Unit = {
    // NOTE(olafur) I must be doing something wrong, but nio.Path.toUri.toString
    // produces file:/// instead of file:/ which is what vscode expects
    val fileUri = s"file:${cwd.resolve(result.path)}"
    connection.publishDiagnostics(fileUri, result.diagnostics)
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    changes.foreach {
      case FileEvent(SemanticdbUri(path),
                     FileChangeType.Created | FileChangeType.Changed) =>
        logger.info(s"$path changed or created. Running scalafix...")
        scalafixService.onSemanticdbPath(path).foreach(report)
      case event =>
        logger.info(s"Unhandled file event: $event")
        ()
    }

  override def onSaveTextDocument(td: TextDocumentIdentifier): Unit = {}

}
