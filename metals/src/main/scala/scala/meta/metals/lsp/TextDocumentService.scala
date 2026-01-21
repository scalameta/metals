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
 * Based on [[org.eclipse.lsp4j.services.TextDocumentService]] with some return types changed.
 */
trait TextDocumentService {

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): CompletableFuture[Unit]

  @JsonNotification("textDocument/didChange")
  def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit]

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): CompletableFuture[Unit]

  @JsonRequest("textDocument/definition")
  def definition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]]

  @JsonRequest("textDocument/typeDefinition")
  def typeDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]]

  @JsonRequest("textDocument/implementation")
  def implementation(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]]

  @JsonRequest("textDocument/hover")
  def hover(params: HoverExtParams): CompletableFuture[Hover]

  @JsonRequest("textDocument/documentHighlight")
  def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]]

  @JsonRequest("textDocument/documentSymbol")
  def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ]

  @JsonRequest("textDocument/formatting")
  def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]]

  @JsonRequest("textDocument/onTypeFormatting")
  def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]]

  @JsonRequest("textDocument/rangeFormatting")
  def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]]

  @JsonRequest("textDocument/prepareRename")
  def prepareRename(
      params: TextDocumentPositionParams
  ): CompletableFuture[l.Range]

  @JsonRequest("textDocument/rename")
  def rename(
      params: RenameParams
  ): CompletableFuture[WorkspaceEdit]

  @JsonRequest("textDocument/references")
  def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]]

  @JsonRequest("textDocument/prepareCallHierarchy")
  def prepareCallHierarchy(
      params: CallHierarchyPrepareParams
  ): CompletableFuture[util.List[CallHierarchyItem]]

  @JsonRequest("callHierarchy/incomingCalls")
  def callHierarchyIncomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): CompletableFuture[util.List[CallHierarchyIncomingCall]]

  @JsonRequest("callHierarchy/outgoingCalls")
  def callHierarchyOutgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): CompletableFuture[util.List[CallHierarchyOutgoingCall]]

  @JsonRequest("textDocument/prepareTypeHierarchy")
  def prepareTypeHierarchy(
      params: TypeHierarchyPrepareParams
  ): CompletableFuture[util.List[TypeHierarchyItem]]

  @JsonRequest("typeHierarchy/supertypes")
  def typeHierarchySupertypes(
      params: TypeHierarchySupertypesParams
  ): CompletableFuture[util.List[TypeHierarchyItem]]

  @JsonRequest("typeHierarchy/subtypes")
  def typeHierarchySubtypes(
      params: TypeHierarchySubtypesParams
  ): CompletableFuture[util.List[TypeHierarchyItem]]

  @JsonRequest("textDocument/completion")
  def completion(params: CompletionParams): CompletableFuture[CompletionList]

  @JsonRequest("completionItem/resolve")
  def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem]

  @JsonRequest("textDocument/signatureHelp")
  def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp]

  @JsonRequest("textDocument/codeAction")
  def codeAction(
      params: CodeActionParams
  ): CompletableFuture[util.List[l.CodeAction]]

  @JsonRequest("codeAction/resolve")
  def codeActionResolve(
      params: CodeAction
  ): CompletableFuture[l.CodeAction]

  @JsonRequest("textDocument/codeLens")
  def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]]

  @JsonRequest("textDocument/foldingRange")
  def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]]

  @JsonRequest("textDocument/selectionRange")
  def selectionRange(
      params: SelectionRangeParams
  ): CompletableFuture[util.List[SelectionRange]]

  /** Requesting semantic tokens for a whole file in order to highlight */
  @JsonRequest("textDocument/semanticTokens/full")
  def semanticTokensFull(
      params: SemanticTokensParams
  ): CompletableFuture[SemanticTokens]

  @JsonRequest("textDocument/inlayHint")
  def inlayHints(
      params: InlayHintParams
  ): CompletableFuture[util.List[InlayHint]]

  @JsonRequest("inlayHint/resolve")
  def inlayHintResolve(
      inlayHint: InlayHint
  ): CompletableFuture[InlayHint]

}
