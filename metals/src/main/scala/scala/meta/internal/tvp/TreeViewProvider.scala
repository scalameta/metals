package scala.meta.internal.tvp

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

trait TreeViewProvider {
  def children(
      params: TreeViewChildrenParams
  ): MetalsTreeViewChildrenResult = MetalsTreeViewChildrenResult(Array.empty)
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
