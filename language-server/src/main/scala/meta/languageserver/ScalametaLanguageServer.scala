package scala.meta.languageserver

import java.io.PrintStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Paths
import scalafix.languageserver.ScalafixResult
import scalafix.languageserver.ScalafixService
import langserver.core.LanguageServer
import langserver.messages._
import langserver.types._
import org.langmeta.io.AbsolutePath

class ScalametaLanguageServer(cwd: AbsolutePath,
                              in: InputStream,
                              out: OutputStream)
    extends LanguageServer(in, out) {
  val ps = new PrintStream(out)
  val scalafixService = new ScalafixService(cwd, out, connection)

  override def initialize(
      pid: Long,
      rootPath: String,
      capabilities: ClientCapabilities): ServerCapabilities = {
    logger.info(s"Initialized with $cwd, $pid, $rootPath, $capabilities")

    ServerCapabilities(completionProvider = None)
  }

  def report(result: ScalafixResult): Unit = {
    // NOTE(olafur) I must be doing something wrong, but nio.Path.toUri.toString
    // produces file:/// instead of file:/ which is what vscode expects
    val fileUri = s"file:${cwd.resolve(result.path)}"
    connection.publishDiagnostics(fileUri, result.diagnostics)
  }

  override def onChangeTextDocument(
      td: VersionedTextDocumentIdentifier,
      changes: Seq[TextDocumentContentChangeEvent]): Unit = {
    connection.showMessage(MessageType.Info, "Calling scalafix...")
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    changes match {
      case FileEvent(uri, FileChangeType.Created | FileChangeType.Changed) +: _ =>
        val semanticdbUri = Paths.get(new URI(uri))
        // TODO(gabro): not sure what is the strategy to manage .semanticdb is going to be
        // but just in case we're being notified here about changes
        logger.info(s"$semanticdbUri changed, created or deleted")
        scalafixService
          .onNewSemanticdb(AbsolutePath(semanticdbUri))
          .foreach(report)
      case _ =>
        ()
    }

}
