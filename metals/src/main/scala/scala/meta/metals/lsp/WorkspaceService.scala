package scala.meta.metals.lsp

import java.util
import java.util.concurrent.CompletableFuture

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

/**
 * Interface which describes workspace LSP requests and notifications which are
 * implemented by Metals.
 *
 * It's equivalent to [[org.eclipse.lsp4j.services.WorkspaceService]] with some return types changed.
 *
 * Each method has a default implementation which throws UnsupportedOperationException. Throwing in this context is ok because:
 * - the default implementation should never be called, because the method is always overridden in [[scala.meta.internal.metals.MetalsLanguageServer]]
 * - lsp4j wraps each method in try catch and returns an error response to the client
 */
trait WorkspaceService {

  @JsonRequest("workspace/symbol")
  def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[util.List[SymbolInformation]] =
    throw new UnsupportedOperationException()

  @JsonRequest("workspace/executeCommand")
  def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object] = throw new UnsupportedOperationException()

  @JsonRequest("workspace/willRenameFiles")
  def willRenameFiles(
      params: RenameFilesParams
  ): CompletableFuture[WorkspaceEdit] =
    throw new UnsupportedOperationException()

  @JsonNotification("workspace/didChangeConfiguration")
  def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] = throw new UnsupportedOperationException()

  @JsonNotification("workspace/didChangeWatchedFiles")
  def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit] = throw new UnsupportedOperationException()

}
