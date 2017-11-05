package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Paths
import java.net.URI
import scalafix.languageserver.ScalafixLintProvider
import scalafmt.languageserver.ScalafmtProvider
import langserver.core.LanguageServer
import langserver.messages.ClientCapabilities
import langserver.messages.FileChangeType
import langserver.messages.FileEvent
import langserver.messages.ServerCapabilities
import langserver.types._
import org.langmeta.io.AbsolutePath

class ScalametaLanguageServer(cwd: AbsolutePath,
                              in: InputStream,
                              out: OutputStream)
    extends LanguageServer(in, out) {
  val ps = new PrintStream(out)
  val scalafixService = new ScalafixLintProvider(cwd, out, connection)
  val scalafmtService = new ScalafmtProvider(cwd, connection)

  override def initialize(
      pid: Long,
      rootPath: String,
      capabilities: ClientCapabilities): ServerCapabilities = {
    logger.info(s"Initialized with $cwd, $pid, $rootPath, $capabilities")

    ServerCapabilities(completionProvider = None,
                       documentFormattingProvider = true)
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

  override def documentFormattingRequest(td: TextDocumentIdentifier,
                                         options: FormattingOptions) = {
    val path = new URI(td.uri)
    val lines = scala.io.Source.fromFile(path).getLines.toList
    val totalLines = lines.length
    val fullDocumentRange = Range(
      start = Position(0, 0),
      end = Position(totalLines, lines.last.length)
    )
    val content = lines.mkString
    val formattedContent = scalafmtService.formatDocument(content)
    List(TextEdit(fullDocumentRange, formattedContent))
  }

  override def onSaveTextDocument(td: TextDocumentIdentifier): Unit = {}

}
