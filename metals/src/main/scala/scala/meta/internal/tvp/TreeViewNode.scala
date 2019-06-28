package scala.meta.internal.tvp

import java.{util => ju}
import javax.annotation.Nullable
import scala.meta.internal.pc.IdentifierComparator
import scala.meta.internal.metals._

case class TreeViewNode(
    viewId: String,
    @Nullable nodeUri: String,
    label: String,
    @Nullable command: MetalsCommand = null,
    @Nullable icon: String = null,
    @Nullable tooltip: String = null,
    // One of "collapsed", "expanded" or "none"
    @Nullable collapseState: String = null
) {
  def isDirectory: Boolean = label.endsWith("/")
  def isCollapsed = collapseState == MetalsTreeItemCollapseState.collapsed
  def isExpanded = collapseState == MetalsTreeItemCollapseState.expanded
  def isNoCollapse = collapseState == MetalsTreeItemCollapseState.none
}

object TreeViewNode {
  def fromCommand(command: Command): TreeViewNode =
    TreeViewNode(
      viewId = "commands",
      nodeUri = s"metals://command/${command.id}",
      label = command.title,
      command = MetalsCommand(
        command.title,
        command.id,
        command.description
      ),
      tooltip = command.description,
      icon = TreeViewNode.command
    )
  def command: String = "command"
  def sortAlphabetically(
      result: Array[TreeViewNode],
      custom: (TreeViewNode, TreeViewNode) => Int = (_, _) => 0
  ): Array[TreeViewNode] = {
    ju.Arrays.sort(
      result,
      (a: TreeViewNode, b: TreeViewNode) => {
        val byCollapse =
          -java.lang.Boolean.compare(a.isDirectory, b.isDirectory)
        if (byCollapse != 0) byCollapse
        else {
          val byCustom = custom(a, b)
          if (byCustom != 0) byCustom
          else IdentifierComparator.compare(a.label, b.label)
        }
      }
    )
    result
  }
}
