package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Paths
import java.util
import java.util.Collections
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services._
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

class MetalsLanguageServer {
  private var workspace = PathIO.workingDirectory
  private var bsp = Option.empty[BuildServerConnection]
  private def connectToBuildServer(): Unit = {
    try {
      val connection = BuildServerConnection.connect(workspace)
      cancelables.addAll(connection.cancelables)
      bsp = Some(connection)
    } catch {
      case NonFatal(e) =>
        scribe.error("Unable to connect to build server", e)
    }
  }
  private val cancelables = new OpenCancelable
  private def updateWorkingDirectory(params: InitializeParams): Unit = {
    workspace = AbsolutePath(Paths.get(URI.create(params.getRootUri)))
    MetalsLogger.setupLspLogger(workspace)
  }
  @JsonRequest("initialize")
  def initialize(
      params: InitializeParams
  ): CompletableFuture[InitializeResult] = {
    updateWorkingDirectory(params)
    val capabilities = new ServerCapabilities()
    capabilities.setDefinitionProvider(true)
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }
  @JsonNotification("initialized")
  def initialized(params: InitializedParams): Unit = {
    connectToBuildServer()
  }
  @JsonRequest("shutdown")
  def shutdown(): CompletableFuture[Object] = {
    cancelables.cancel()
    CompletableFuture.completedFuture(null)
  }
  @JsonNotification("exit")
  def exit(): Unit = {
    sys.exit(0)
  }

  @JsonNotification("textDocument/didOpen")
  def textDocumentDidOpen(params: DidOpenTextDocumentParams): Unit = ()
  @JsonNotification("textDocument/didChange")
  def textDocumentDidChange(params: DidChangeTextDocumentParams): Unit = ()
  @JsonNotification("textDocument/didClose")
  def textDocumentDidClose(params: DidCloseTextDocumentParams): Unit = ()
  @JsonNotification("textDocument/didSave")
  def textDocumentDidSave(params: DidSaveTextDocumentParams): Unit = ()

  @JsonNotification("workspace/didChangeConfiguration")
  def workspaceDidChangeConfiguration(
      params: DidChangeConfigurationParams
  ): Unit = ()
  @JsonNotification("workspace/didChangeWatchedFiles")
  def workspaceDidChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): Unit = ()

  @JsonRequest("textDocument/definition")
  def textDocumentDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] = {
    val pos = position.getPosition
    pos.setLine(pos.getLine - 1)
    val range = new Range(pos, pos)
    val location = new Location(position.getTextDocument.getUri, range)
    CompletableFuture.completedFuture(Collections.singletonList(location))
  }

  def connect(client: LanguageClient): Unit = {
    LanguageClientLogger.languageClient = Some(client)
  }
}
