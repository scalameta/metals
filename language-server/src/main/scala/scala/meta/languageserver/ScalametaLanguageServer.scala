package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.control.NonFatal
import scalafix.languageserver.ScalafixLintProvider
import langserver.core.LanguageServer
import langserver.messages.ClientCapabilities
import langserver.messages.FileChangeType
import langserver.messages.FileEvent
import langserver.messages.MessageType
import langserver.messages.ServerCapabilities
import langserver.types._
import org.langmeta.io.AbsolutePath

class ScalametaLanguageServer(
    cwd: AbsolutePath,
    in: InputStream,
    out: OutputStream,
    scalafmt: Formatter
) extends LanguageServer(in, out) {
  val ps = new PrintStream(out)
  val buffers = Buffers()
  val scalafixService = new ScalafixLintProvider(cwd, out, connection)
  def read(path: AbsolutePath): String =
    new String(Files.readAllBytes(path.toNIO), StandardCharsets.UTF_8)

  override def initialize(
      pid: Long,
      rootPath: String,
      capabilities: ClientCapabilities
  ): ServerCapabilities = {
    logger.info(s"Initialized with $cwd, $pid, $rootPath, $capabilities")

    ServerCapabilities(
      completionProvider = None,
      documentFormattingProvider = true
    )
  }

  def report(result: DiagnosticsReport): Unit = {
    // NOTE(olafur) I must be doing something wrong, but nio.Path.toUri.toString
    // produces file:/// instead of file:/ which is what vscode expects
    val fileUri = s"file:${cwd.resolve(result.path)}"
    connection.publishDiagnostics(fileUri, result.diagnostics)
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    changes.foreach {
      case FileEvent(
          uri @ Uri(path),
          FileChangeType.Created | FileChangeType.Changed
          ) if uri.endsWith(".semanticdb") =>
        logger.info(s"$path changed or created. Running scalafix...")
        scalafixService.onSemanticdbPath(path).foreach(report)
      case event =>
        logger.info(s"Unhandled file event: $event")
        ()
    }

  override def documentFormattingRequest(
      td: TextDocumentIdentifier,
      options: FormattingOptions
  ): List[TextEdit] = {
    try {
      val path = Uri.toPath(td.uri).get
      val contents = buffers.read(td.uri).getOrElse(read(path))
      val fullDocumentRange = Range(
        start = Position(0, 0),
        end = Position(Int.MaxValue, Int.MaxValue)
      )
      connection.showMessage(MessageType.Info, s"Running scalafmt on $path...")
      val formattedContent = scalafmt.format(
        contents,
        cwd.resolve(".scalafmt.conf").toString(),
        path.toString()
      )
      List(TextEdit(fullDocumentRange, formattedContent))
    } catch {
      case NonFatal(e) =>
        connection.showMessage(MessageType.Error, e.getMessage)
        logger.error(e.getMessage, e)
        Nil
    }
  }

  override def onOpenTextDocument(td: TextDocumentItem): Unit =
    buffers.changed(td.uri, td.text)
  override def onChangeTextDocument(
      td: VersionedTextDocumentIdentifier,
      changes: Seq[TextDocumentContentChangeEvent]
  ): Unit = {
    changes.foreach { c =>
      buffers.changed(td.uri, c.text)
    }
  }

  override def onSaveTextDocument(td: TextDocumentIdentifier): Unit = {}

}
