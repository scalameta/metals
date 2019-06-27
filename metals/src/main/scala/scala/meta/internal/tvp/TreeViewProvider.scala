package scala.meta.internal.tvp

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.io.AbsolutePath
import org.eclipse.{lsp4j => l}

trait TreeViewProvider {
  val Build = TreeViewProvider.Build
  val Compile = TreeViewProvider.Compile
  val Help = TreeViewProvider.Help
  def didFocusTextDocument(
      path: AbsolutePath
  ): Unit = ()
  def children(
      params: TreeViewChildrenParams
  ): MetalsTreeViewChildrenResult = MetalsTreeViewChildrenResult(Array.empty)
  def reveal(
      path: AbsolutePath,
      pos: l.Position
  ): Option[TreeViewNodeRevealResult] = None
  def onCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): Unit = ()
  def parent(
      params: TreeViewParentParams
  ): TreeViewParentResult = TreeViewParentResult()
  def onVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): Unit = ()
  def onBuildTargetDidCompile(
      id: BuildTargetIdentifier
  ): Unit = ()
}

object TreeViewProvider {
  val Build = "metalsBuild"
  val Compile = "metalsCompile"
  val Help = "metalsHelp"
}
