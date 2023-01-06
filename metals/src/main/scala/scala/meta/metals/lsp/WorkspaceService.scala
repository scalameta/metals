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
 * Based on [[org.eclipse.lsp4j.services.WorkspaceService]] with some return types changed.
 */
trait WorkspaceService {

  @JsonRequest("workspace/symbol")
  def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[util.List[SymbolInformation]]

  @JsonRequest("workspace/executeCommand")
  def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object]

  @JsonRequest("workspace/willRenameFiles")
  def willRenameFiles(
      params: RenameFilesParams
  ): CompletableFuture[WorkspaceEdit]

  @JsonNotification("workspace/didChangeConfiguration")
  def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit]

  @JsonNotification("workspace/didChangeWatchedFiles")
  def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit]

}
