package scala.meta.internal.tvp

import javax.annotation.Nullable
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

trait TreeViewClient {
  @JsonNotification("metals/treeViewDidChange")
  def metalsTreeViewDidChange(params: TreeViewDidChangeParams): Unit
}

case class TreeViewChildrenParams(
    viewId: String,
    @Nullable nodeUri: String = null
)

case class TreeViewParentParams(
    viewId: String,
    @Nullable nodeUri: String = null
)

case class TreeViewParentResult(
    @Nullable uri: String = null
)

case class TreeViewVisibilityDidChangeParams(
    viewId: String,
    visible: java.lang.Boolean
)

case class TreeViewNodeCollapseDidChangeParams(
    viewId: String,
    nodeUri: String,
    collapsed: java.lang.Boolean
)

case class MetalsTreeViewChildrenResult(
    nodes: Array[TreeViewNode]
)

object MetalsTreeItemCollapseState {
  def collapsed: String = "collapsed"
  def expanded: String = "expanded"
  def none: String = null
}

case class MetalsCommand(
    title: String,
    command: String,
    @Nullable tooltip: String = null,
    @Nullable arguments: Array[AnyRef] = null
)

object MetalsCommand {
  def goto(symbol: String): MetalsCommand =
    MetalsCommand(
      "Go to Definition",
      "metals.goto",
      symbol,
      Array(symbol)
    )
}

case class TreeViewDidChangeParams(
    nodes: Array[TreeViewNode]
)

case class TreeViewRevealNodeParams(
    viewId: String,
    uri: String,
    @Nullable expand: java.lang.Integer = null,
    @Nullable select: java.lang.Boolean = null,
    @Nullable focus: java.lang.Boolean = null
)
