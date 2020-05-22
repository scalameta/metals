package scala.meta.internal.tvp

import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.{lsp4j => l}

trait TreeViewProvider {
  val Project = TreeViewProvider.Project
  val Build = TreeViewProvider.Build
  val Help = TreeViewProvider.Help
  val Compile = TreeViewProvider.Compile
  def init(): Unit = ()
  def reset(): Unit = ()
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
  val Project = "metalsPackages"
  val Build = "metalsBuild"
  val Compile = "metalsCompile"
  val Help = "metalsHelp"
}
