package scala.meta.metals.lsp

import java.util
import java.util.concurrent.CompletableFuture

import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.WindowStateDidChangeParams
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
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

/**
 * Interface which describes metals specific LSP requests and notifications which are
 * implemented by Metals.
 *
 * Each method has a default implementation which throws UnsupportedOperationException. Throwing in this context is ok because:
 * - the default implementation should never be called, because the method is always overridden in [[scala.meta.internal.metals.MetalsLanguageServer]]
 * - lsp4j wraps each method in try catch and returns an error response to the client
 */
trait MetalsService {
  @JsonRequest("metals/treeViewChildren")
  def treeViewChildren(
      params: TreeViewChildrenParams
  ): CompletableFuture[MetalsTreeViewChildrenResult] =
    throw new UnsupportedOperationException()

  @JsonRequest("metals/treeViewParent")
  def treeViewParent(
      params: TreeViewParentParams
  ): CompletableFuture[TreeViewParentResult] =
    throw new UnsupportedOperationException()

  @JsonRequest("metals/treeViewReveal")
  def treeViewReveal(
      params: TextDocumentPositionParams
  ): CompletableFuture[TreeViewNodeRevealResult] =
    throw new UnsupportedOperationException()

  @JsonRequest("metals/findTextInDependencyJars")
  def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): CompletableFuture[util.List[Location]] =
    throw new UnsupportedOperationException()

  @JsonNotification("metals/doctorVisibilityDidChange")
  def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit] = throw new UnsupportedOperationException()

  @JsonNotification("metals/treeViewVisibilityDidChange")
  def treeViewVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): CompletableFuture[Unit] = throw new UnsupportedOperationException()

  @JsonNotification("metals/treeViewNodeCollapseDidChange")
  def treeViewNodeCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): CompletableFuture[Unit] = throw new UnsupportedOperationException()

  @JsonNotification("metals/didFocusTextDocument")
  def didFocus(
      params: AnyRef
  ): CompletableFuture[DidFocusResult.Value] =
    throw new UnsupportedOperationException()

  @JsonNotification("metals/windowStateDidChange")
  def windowStateDidChange(params: WindowStateDidChangeParams): Unit =
    throw new UnsupportedOperationException()
}
