package scala.meta.metals.lsp

import java.util
import java.util.concurrent.CompletableFuture

import scala.meta.internal.metals.HoverExtParams

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.{lsp4j => l}

/**
 * Interface which describes text document LSP requests and notifications which are
 * implemented by Metals.
 *
 * It's equivalent to [[org.eclipse.lsp4j.services.TextDocumentService]] with some return types changed.
 *
 * Each method has a default implementation which throws UnsupportedOperationException. Throwing in this context is ok because:
 * - the default implementation should never be called, because the method is always overridden in [[scala.meta.internal.metals.MetalsLanguageServer]]
 * - lsp4j wraps each method in try catch and returns an error response to the client
 */
trait TextDocumentService {

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): CompletableFuture[Unit] =
    throw new UnsupportedOperationException()

  @JsonNotification("textDocument/didChange")
  def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] = throw new UnsupportedOperationException()

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit =
    throw new UnsupportedOperationException()

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): CompletableFuture[Unit] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/definition")
  def definition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/typeDefinition")
  def typeDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/implementation")
  def implementation(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/hover")
  def hover(params: HoverExtParams): CompletableFuture[Hover] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/documentHighlight")
  def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/documentSymbol")
  def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ] = throw new UnsupportedOperationException()

  @JsonRequest("textDocument/formatting")
  def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/onTypeFormatting")
  def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/rangeFormatting")
  def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/prepareRename")
  def prepareRename(
      params: TextDocumentPositionParams
  ): CompletableFuture[l.Range] = throw new UnsupportedOperationException()

  @JsonRequest("textDocument/rename")
  def rename(
      params: RenameParams
  ): CompletableFuture[WorkspaceEdit] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/references")
  def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/prepareCallHierarchy")
  def prepareCallHierarchy(
      params: CallHierarchyPrepareParams
  ): CompletableFuture[util.List[CallHierarchyItem]] =
    throw new UnsupportedOperationException()

  @JsonRequest("callHierarchy/incomingCalls")
  def callHierarchyIncomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    throw new UnsupportedOperationException()

  @JsonRequest("callHierarchy/outgoingCalls")
  def callHierarchyOutgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/completion")
  def completion(params: CompletionParams): CompletableFuture[CompletionList] =
    throw new UnsupportedOperationException()

  @JsonRequest("completionItem/resolve")
  def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/signatureHelp")
  def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/codeAction")
  def codeAction(
      params: CodeActionParams
  ): CompletableFuture[util.List[l.CodeAction]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/codeLens")
  def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/foldingRange")
  def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] =
    throw new UnsupportedOperationException()

  @JsonRequest("textDocument/selectionRange")
  def selectionRange(
      params: SelectionRangeParams
  ): CompletableFuture[util.List[SelectionRange]] =
    throw new UnsupportedOperationException()

}
