package scala.meta.metals.lsp

import java.util.concurrent.CompletableFuture

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

/**
 * Modified version of [[org.eclipse.lsp4j.services.LanguageServer]] that has
 * adjusted types for already existing Metals methods. Moreover, this interface
 * has only one delegate method which provides exactly the same methods as
 * current [[scala.meta.metals.internal.MetalLanguageServer]] does.
 *
 * Delegation of LSP request allows us to delay creation of
 * TextDocumentAndWorkspaceService (which is MetalsLanguageServer under the
 * hood) until [[initialize]] is called. Previously, we were creating it eagerly
 * with many services set to null because we didn't have access to the client,
 * workspace and configuration yet.
 */
trait LanguageServer {
  @JsonRequest("initialize")
  def initialize(
      params: InitializeParams
  ): CompletableFuture[InitializeResult]

  @JsonNotification("initialized")
  def initialized(params: InitializedParams): CompletableFuture[Unit]

  @JsonRequest("shutdown")
  def shutdown(): CompletableFuture[Unit]

  @JsonNotification("exit")
  def exit(): Unit

  /**
   * See [[scala.meta.metals.lsp.DelegatingScalaService]] docs for more information about how JsonDelegate works.
   */
  @JsonDelegate
  def getScalaService: ScalaLspService
}

trait ScalaLspService
    extends TextDocumentService
    with WorkspaceService
    with MetalsService
    with WindowService
