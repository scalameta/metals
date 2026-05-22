package scala.meta.internal.metals

import java.util
import java.util.concurrent.CompletableFuture

import scala.meta.metals.lsp.ScalaLspService
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.doctor.DoctorVisibilityDidChangeParams
import scala.meta.internal.metals.findfiles.FindTextInDependencyJarsRequest
import scala.meta.internal.tvp.{
  MetalsTreeViewChildrenResult,
  TreeViewChildrenParams,
  TreeViewNodeCollapseDidChangeParams,
  TreeViewNodeRevealResult,
  TreeViewParentParams,
  TreeViewParentResult,
  TreeViewVisibilityDidChangeParams
}

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

/**
 * Decorator that adds OpenTelemetry tracing spans to every LSP request.
 * Delegates all method calls to the underlying ScalaLspService.
 */
class TracedScalaLspService(
    underlying: ScalaLspService
) extends ScalaLspService {

  import MetalsTracer._

  private def traceRequest[T](
      method: String,
      attributes: (String, String)*
  )(action: => CompletableFuture[T]): CompletableFuture[T] = {
    val span = startSpan(method, attributes: _*)
    try {
      action.whenComplete { (_, error) =>
        if (error != null) recordException(span, error)
        endSpan(span)
      }
    } catch {
      case scala.util.control.NonFatal(e) =>
        recordException(span, e)
        endSpan(span)
        throw e
    }
  }

  // ---- TextDocumentService ----

  override def didOpen(
      params: DidOpenTextDocumentParams
  ): CompletableFuture[Unit] =
    traceRequest("textDocument/didOpen", "file.uri" -> params.getTextDocument.getUri) {
      underlying.didOpen(params)
    }

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] =
    traceRequest("textDocument/didChange", "file.uri" -> params.getTextDocument.getUri) {
      underlying.didChange(params)
    }

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    underlying.didClose(params)

  override def didSave(
      params: DidSaveTextDocumentParams
  ): CompletableFuture[Unit] =
    traceRequest("textDocument/didSave", "file.uri" -> params.getTextDocument.getUri) {
      underlying.didSave(params)
    }

  override def definition(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/definition", "file.uri" -> params.getTextDocument.getUri) {
      underlying.definition(params)
    }

  override def typeDefinition(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/typeDefinition", "file.uri" -> params.getTextDocument.getUri) {
      underlying.typeDefinition(params)
    }

  override def implementation(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/implementation", "file.uri" -> params.getTextDocument.getUri) {
      underlying.implementation(params)
    }

  override def hover(params: HoverExtParams): CompletableFuture[Hover] =
    traceRequest("textDocument/hover", "file.uri" -> params.textDocument.getUri) {
      underlying.hover(params)
    }

  override def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    traceRequest("textDocument/documentHighlight", "file.uri" -> params.getTextDocument.getUri) {
      underlying.documentHighlights(params)
    }

  override def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]] =
    traceRequest("textDocument/documentSymbol", "file.uri" -> params.getTextDocument.getUri) {
      underlying.documentSymbol(params)
    }

  override def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    traceRequest("textDocument/formatting", "file.uri" -> params.getTextDocument.getUri) {
      underlying.formatting(params)
    }

  override def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    traceRequest("textDocument/onTypeFormatting", "file.uri" -> params.getTextDocument.getUri) {
      underlying.onTypeFormatting(params)
    }

  override def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    traceRequest("textDocument/rangeFormatting", "file.uri" -> params.getTextDocument.getUri) {
      underlying.rangeFormatting(params)
    }

  override def prepareRename(
      params: TextDocumentPositionParams
  ): CompletableFuture[Range] =
    traceRequest("textDocument/prepareRename", "file.uri" -> params.getTextDocument.getUri) {
      underlying.prepareRename(params)
    }

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    traceRequest("textDocument/rename", "file.uri" -> params.getTextDocument.getUri) {
      underlying.rename(params)
    }

  override def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]] =
    traceRequest("textDocument/references", "file.uri" -> params.getTextDocument.getUri) {
      underlying.references(params)
    }

  override def prepareCallHierarchy(
      params: CallHierarchyPrepareParams
  ): CompletableFuture[util.List[CallHierarchyItem]] =
    traceRequest("textDocument/prepareCallHierarchy", "file.uri" -> params.getTextDocument.getUri) {
      underlying.prepareCallHierarchy(params)
    }

  override def callHierarchyIncomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    traceRequest("callHierarchy/incomingCalls") {
      underlying.callHierarchyIncomingCalls(params)
    }

  override def callHierarchyOutgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    traceRequest("callHierarchy/outgoingCalls") {
      underlying.callHierarchyOutgoingCalls(params)
    }

  override def prepareTypeHierarchy(
      params: TypeHierarchyPrepareParams
  ): CompletableFuture[util.List[TypeHierarchyItem]] =
    traceRequest("textDocument/prepareTypeHierarchy", "file.uri" -> params.getTextDocument.getUri) {
      underlying.prepareTypeHierarchy(params)
    }

  override def typeHierarchySupertypes(
      params: TypeHierarchySupertypesParams
  ): CompletableFuture[util.List[TypeHierarchyItem]] =
    traceRequest("typeHierarchy/supertypes") {
      underlying.typeHierarchySupertypes(params)
    }

  override def typeHierarchySubtypes(
      params: TypeHierarchySubtypesParams
  ): CompletableFuture[util.List[TypeHierarchyItem]] =
    traceRequest("typeHierarchy/subtypes") {
      underlying.typeHierarchySubtypes(params)
    }

  override def completion(
      params: CompletionParams
  ): CompletableFuture[CompletionList] =
    traceRequest("textDocument/completion", "file.uri" -> params.getTextDocument.getUri) {
      underlying.completion(params)
    }

  override def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem] =
    traceRequest("completionItem/resolve") {
      underlying.completionItemResolve(item)
    }

  override def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    traceRequest("textDocument/signatureHelp", "file.uri" -> params.getTextDocument.getUri) {
      underlying.signatureHelp(params)
    }

  override def codeAction(
      params: CodeActionParams
  ): CompletableFuture[util.List[CodeAction]] =
    traceRequest("textDocument/codeAction", "file.uri" -> params.getTextDocument.getUri) {
      underlying.codeAction(params)
    }

  override def codeActionResolve(
      params: CodeAction
  ): CompletableFuture[CodeAction] =
    traceRequest("codeAction/resolve") {
      underlying.codeActionResolve(params)
    }

  override def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] =
    traceRequest("textDocument/codeLens", "file.uri" -> params.getTextDocument.getUri) {
      underlying.codeLens(params)
    }

  override def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] =
    traceRequest("textDocument/foldingRange", "file.uri" -> params.getTextDocument.getUri) {
      underlying.foldingRange(params)
    }

  override def selectionRange(
      params: SelectionRangeParams
  ): CompletableFuture[util.List[SelectionRange]] =
    traceRequest("textDocument/selectionRange", "file.uri" -> params.getTextDocument.getUri) {
      underlying.selectionRange(params)
    }

  override def semanticTokensFull(
      params: SemanticTokensParams
  ): CompletableFuture[SemanticTokens] =
    traceRequest("textDocument/semanticTokens/full", "file.uri" -> params.getTextDocument.getUri) {
      underlying.semanticTokensFull(params)
    }

  override def inlayHints(
      params: InlayHintParams
  ): CompletableFuture[util.List[InlayHint]] =
    traceRequest("textDocument/inlayHint", "file.uri" -> params.getTextDocument.getUri) {
      underlying.inlayHints(params)
    }

  override def inlayHintResolve(
      inlayHint: InlayHint
  ): CompletableFuture[InlayHint] =
    traceRequest("inlayHint/resolve") {
      underlying.inlayHintResolve(inlayHint)
    }

  // ---- WorkspaceService ----

  override def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[util.List[SymbolInformation]] =
    traceRequest("workspace/symbol", "query" -> params.getQuery) {
      underlying.workspaceSymbol(params)
    }

  override def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object] =
    traceRequest("workspace/executeCommand", "command" -> params.getCommand) {
      underlying.executeCommand(params)
    }

  override def willRenameFiles(
      params: RenameFilesParams
  ): CompletableFuture[WorkspaceEdit] =
    traceRequest("workspace/willRenameFiles") {
      underlying.willRenameFiles(params)
    }

  override def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] =
    traceRequest("workspace/didChangeConfiguration") {
      underlying.didChangeConfiguration(params)
    }

  override def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit] =
    traceRequest("workspace/didChangeWatchedFiles") {
      underlying.didChangeWatchedFiles(params)
    }

  override def didChangeWorkspaceFolders(
      params: DidChangeWorkspaceFoldersParams
  ): CompletableFuture[Unit] =
    traceRequest("workspace/didChangeWorkspaceFolders") {
      underlying.didChangeWorkspaceFolders(params)
    }

  // ---- MetalsService ----

  override def treeViewChildren(
      params: TreeViewChildrenParams
  ): CompletableFuture[MetalsTreeViewChildrenResult] =
    traceRequest("metals/treeViewChildren", "viewId" -> params.viewId) {
      underlying.treeViewChildren(params)
    }

  override def treeViewParent(
      params: TreeViewParentParams
  ): CompletableFuture[TreeViewParentResult] =
    traceRequest("metals/treeViewParent", "viewId" -> params.viewId) {
      underlying.treeViewParent(params)
    }

  override def treeViewReveal(
      params: TextDocumentPositionParams
  ): CompletableFuture[TreeViewNodeRevealResult] =
    traceRequest("metals/treeViewReveal", "file.uri" -> params.getTextDocument.getUri) {
      underlying.treeViewReveal(params)
    }

  override def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): CompletableFuture[util.List[Location]] =
    traceRequest("metals/findTextInDependencyJars", "query" -> params.query.pattern) {
      underlying.findTextInDependencyJars(params)
    }

  override def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit] =
    traceRequest("metals/doctorVisibilityDidChange") {
      underlying.doctorVisibilityDidChange(params)
    }

  override def treeViewVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): CompletableFuture[Unit] =
    traceRequest("metals/treeViewVisibilityDidChange", "viewId" -> params.viewId) {
      underlying.treeViewVisibilityDidChange(params)
    }

  override def treeViewNodeCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): CompletableFuture[Unit] =
    traceRequest("metals/treeViewNodeCollapseDidChange", "viewId" -> params.viewId) {
      underlying.treeViewNodeCollapseDidChange(params)
    }

  override def didFocus(
      params: AnyRef
  ): CompletableFuture[DidFocusResult.Value] =
    traceRequest("metals/didFocusTextDocument", "file.uri" -> params.toString) {
      underlying.didFocus(params)
    }

  // ---- WindowService ----

  override def didCancelWorkDoneProgress(
      params: WorkDoneProgressCancelParams
  ): Unit = underlying.didCancelWorkDoneProgress(params)
}
