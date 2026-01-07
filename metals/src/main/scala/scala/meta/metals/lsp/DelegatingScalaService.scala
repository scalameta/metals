package scala.meta.metals.lsp

import java.util
import java.util.concurrent.CompletableFuture

import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.doctor.DoctorVisibilityDidChangeParams
import scala.meta.internal.metals.findfiles.FindTextInDependencyJarsRequest
import scala.meta.internal.tvp.MetalsTreeViewChildrenResult
import scala.meta.internal.tvp.TreeViewChildrenParams
import scala.meta.internal.tvp.TreeViewNodeCollapseDidChangeParams
import scala.meta.internal.tvp.TreeViewNodeRevealResult
import scala.meta.internal.tvp.TreeViewParentParams
import scala.meta.internal.tvp.TreeViewParentResult
import scala.meta.internal.tvp.TreeViewVisibilityDidChangeParams

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

/**
 * Delegating Scala LSP service which forwards all requests to the underlying
 * instance. This is needed to support lsp4j
 * [[org.eclipse.lsp4j.jsonrpc.services.JsonDelegate]]. Value returned by
 * method with that annotation is used to determine which methods are supported
 * by the server. We can't initialize it to null because then no lsp methods would be
 * supported. Instead, we initialize it to a dummy instance which is then replaced.
 *
 * @param underlying
 *   underlying instance which is swapped at runtime
 */
class DelegatingScalaService(
    @volatile var underlying: ScalaLspService
) extends ScalaLspService {

  override def didOpen(
      params: DidOpenTextDocumentParams
  ): CompletableFuture[Unit] = underlying.didOpen(params)

  override def didFocus(
      params: AnyRef
  ): CompletableFuture[DidFocusResult.Value] = underlying.didFocus(params)

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] = underlying.didChange(params)

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    underlying.didClose(params)

  override def didSave(
      params: DidSaveTextDocumentParams
  ): CompletableFuture[Unit] = underlying.didSave(params)

  override def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] = underlying.didChangeConfiguration(params)

  override def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit] = underlying.didChangeWatchedFiles(params)

  override def definition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] = underlying.definition(position)

  override def typeDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    underlying.typeDefinition(position)

  override def implementation(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    underlying.implementation(position)

  override def hover(params: HoverExtParams): CompletableFuture[Hover] =
    underlying.hover(params)

  override def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    underlying.documentHighlights(params)

  override def documentSymbol(params: DocumentSymbolParams): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ] = underlying.documentSymbol(params)

  override def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] = underlying.formatting(params)

  override def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    underlying.onTypeFormatting(params)

  override def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] = underlying.rangeFormatting(params)

  override def prepareRename(
      params: TextDocumentPositionParams
  ): CompletableFuture[Range] = underlying.prepareRename(params)

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    underlying.rename(params)

  override def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]] = underlying.references(params)

  override def prepareCallHierarchy(
      params: CallHierarchyPrepareParams
  ): CompletableFuture[util.List[CallHierarchyItem]] =
    underlying.prepareCallHierarchy(params)

  override def callHierarchyIncomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    underlying.callHierarchyIncomingCalls(params)

  override def callHierarchyOutgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    underlying.callHierarchyOutgoingCalls(params)

  override def prepareTypeHierarchy(
      params: TypeHierarchyPrepareParams
  ): CompletableFuture[util.List[TypeHierarchyItem]] =
    underlying.prepareTypeHierarchy(params)

  override def typeHierarchySupertypes(
      params: TypeHierarchySupertypesParams
  ): CompletableFuture[util.List[TypeHierarchyItem]] =
    underlying.typeHierarchySupertypes(params)

  override def typeHierarchySubtypes(
      params: TypeHierarchySubtypesParams
  ): CompletableFuture[util.List[TypeHierarchyItem]] =
    underlying.typeHierarchySubtypes(params)

  override def completion(
      params: CompletionParams
  ): CompletableFuture[CompletionList] = underlying.completion(params)

  override def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem] = underlying.completionItemResolve(item)

  override def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] = underlying.signatureHelp(params)

  override def codeAction(
      params: CodeActionParams
  ): CompletableFuture[util.List[CodeAction]] = underlying.codeAction(params)

  override def codeActionResolve(
      params: CodeAction
  ): CompletableFuture[CodeAction] = underlying.codeActionResolve(params)

  override def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] = underlying.codeLens(params)

  override def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] =
    underlying.foldingRange(params)

  override def selectionRange(
      params: SelectionRangeParams
  ): CompletableFuture[util.List[SelectionRange]] =
    underlying.selectionRange(params)

  override def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[util.List[SymbolInformation]] =
    underlying.workspaceSymbol(params)

  override def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object] = underlying.executeCommand(params)

  override def willRenameFiles(
      params: RenameFilesParams
  ): CompletableFuture[WorkspaceEdit] = underlying.willRenameFiles(params)

  override def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit] = underlying.doctorVisibilityDidChange(params)

  override def treeViewChildren(
      params: TreeViewChildrenParams
  ): CompletableFuture[MetalsTreeViewChildrenResult] =
    underlying.treeViewChildren(params)

  override def treeViewParent(
      params: TreeViewParentParams
  ): CompletableFuture[TreeViewParentResult] = underlying.treeViewParent(params)

  override def treeViewVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): CompletableFuture[Unit] = underlying.treeViewVisibilityDidChange(params)

  override def treeViewNodeCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): CompletableFuture[Unit] = underlying.treeViewNodeCollapseDidChange(params)

  override def treeViewReveal(
      params: TextDocumentPositionParams
  ): CompletableFuture[TreeViewNodeRevealResult] =
    underlying.treeViewReveal(params)

  override def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): CompletableFuture[util.List[Location]] =
    underlying.findTextInDependencyJars(params)

  override def semanticTokensFull(
      params: SemanticTokensParams
  ): CompletableFuture[SemanticTokens] = underlying.semanticTokensFull(params)

  override def inlayHints(
      params: InlayHintParams
  ): CompletableFuture[util.List[InlayHint]] =
    underlying.inlayHints(params)

  override def inlayHintResolve(
      inlayHint: InlayHint
  ): CompletableFuture[InlayHint] =
    underlying.inlayHintResolve(inlayHint)

  override def didChangeWorkspaceFolders(
      params: DidChangeWorkspaceFoldersParams
  ): CompletableFuture[Unit] = underlying.didChangeWorkspaceFolders(params)

  override def didCancelWorkDoneProgress(
      params: WorkDoneProgressCancelParams
  ): Unit = underlying.didCancelWorkDoneProgress(params)

}
