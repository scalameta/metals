package scala.meta.metals.lsp

import org.eclipse.lsp4j.DidChangeNotebookDocumentParams
import org.eclipse.lsp4j.DidCloseNotebookDocumentParams
import org.eclipse.lsp4j.DidOpenNotebookDocumentParams
import org.eclipse.lsp4j.DidSaveNotebookDocumentParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

/**
 * Notebook document synchronization
 * (https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#notebookDocument_synchronization),
 * used to give Scala notebook cells (`vscode-notebook-cell:` documents,
 * see https://github.com/scalameta/metals-feature-requests/issues/236)
 * language support. See [[scala.meta.internal.metals.NotebookProvider]].
 */
trait NotebookDocumentService {

  @JsonNotification("notebookDocument/didOpen")
  def notebookDidOpen(params: DidOpenNotebookDocumentParams): Unit

  @JsonNotification("notebookDocument/didChange")
  def notebookDidChange(params: DidChangeNotebookDocumentParams): Unit

  @JsonNotification("notebookDocument/didSave")
  def notebookDidSave(params: DidSaveNotebookDocumentParams): Unit

  @JsonNotification("notebookDocument/didClose")
  def notebookDidClose(params: DidCloseNotebookDocumentParams): Unit

}
