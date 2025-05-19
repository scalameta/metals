package scala.meta.metals.lsp

import java.util
import java.util.concurrent.CompletableFuture

import scala.meta.internal.metals.DidFocusResult
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
 */
trait MetalsService {
  @JsonRequest("metals/treeViewChildren")
  def treeViewChildren(
      params: TreeViewChildrenParams
  ): CompletableFuture[MetalsTreeViewChildrenResult]

  @JsonRequest("metals/treeViewParent")
  def treeViewParent(
      params: TreeViewParentParams
  ): CompletableFuture[TreeViewParentResult]

  @JsonRequest("metals/treeViewReveal")
  def treeViewReveal(
      params: TextDocumentPositionParams
  ): CompletableFuture[TreeViewNodeRevealResult]

  @JsonRequest("metals/findTextInDependencyJars")
  def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): CompletableFuture[util.List[Location]]

  @JsonNotification("metals/doctorVisibilityDidChange")
  def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit]

  @JsonNotification("metals/treeViewVisibilityDidChange")
  def treeViewVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): CompletableFuture[Unit]

  @JsonNotification("metals/treeViewNodeCollapseDidChange")
  def treeViewNodeCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): CompletableFuture[Unit]

  @JsonNotification("metals/didFocusTextDocument")
  def didFocus(
      params: AnyRef
  ): CompletableFuture[DidFocusResult.Value]

  @JsonNotification("metals/sync")
  def sync(
      params: AnyRef
  ): CompletableFuture[Unit]

}
