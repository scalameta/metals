package scala.meta.languageserver

import java.io.{File, InputStream, OutputStream}
import java.net.URI

import langserver.core.{LanguageServer, TextDocument, MessageReader}
import langserver.messages._
import langserver.types._

class ScalametaLanguageServer(in: InputStream, out: OutputStream) extends LanguageServer(in, out) {

  override def initialize(pid: Long, rootPath: String, capabilities: ClientCapabilities): ServerCapabilities = {
    logger.info(s"Initialized with $pid, $rootPath, $capabilities")

    ServerCapabilities(completionProvider = None)
  }

  private[this] def runScalafixLint(uri: String): List[Diagnostic] = {
    // TODO(gabro): here's where we have to invoke scalafix and collect all the linter errors/warnings

    // Dummy example of Diagnostic
    List(Diagnostic(
      range = Range(start = Position(line = 5, character = 0), end = Position(line = 5, character = 6)),
      severity = Some(DiagnosticSeverity.Error),
      code = None,
      source = Some("scalafix"),
      message = "Hey, this is not good"
    ))

  }

  override def onChangeTextDocument(td: VersionedTextDocumentIdentifier, changes: Seq[TextDocumentContentChangeEvent]): Unit = {
    val diagnostics = runScalafixLint(td.uri)
    connection.publishDiagnostics(td.uri, diagnostics)
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit = changes match {
    case FileEvent(uri, FileChangeType.Created | FileChangeType.Changed | FileChangeType.Deleted) +: _ =>
      // TODO(gabro): not sure what is the strategy to manage .semanticdb is going to be
      // but just in case we're being notified here about changes
      logger.info(s"$uri changed, created or deleted")
    case _ => ()
  }

}
