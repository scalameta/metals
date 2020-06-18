package scala.meta.internal.tvp

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}

/**
 * A tree view that exposes a package explorer to browse symbols such as classes, traits and methods.
 *
 * @param Value The metadata used to render the toplevel nodes such as jar files or
 *              a Scala build target (with scala version, classpath, ...).
 * @param Key The identifier key that is persisted into the URI of tree view nodes,
 *            such as a jar file URI or a build target URI.
 */
class ClasspathTreeView[Value, Key](
    definitionIndex: GlobalSymbolIndex,
    viewId: String,
    scheme: String,
    title: String,
    id: Value => Key,
    encode: Key => String,
    decode: String => Key,
    valueTitle: Value => String,
    valueTooltip: Value => String,
    toplevels: () => Iterator[Value],
    loadSymbols: (Key, String) => Iterator[TreeViewSymbolInformation]
) {
  val rootUri: String = scheme + ":"
  def root: TreeViewNode =
    TreeViewNode(
      viewId,
      rootUri,
      title + s" (${toplevels().size})",
      collapseState = MetalsTreeItemCollapseState.collapsed
    )

  def matches(uri: String): Boolean = uri.startsWith(rootUri)

  def parent(uri: String): Option[String] =
    if (uri == rootUri) None
    else fromUri(uri).parent.map(_.toUri)

  def children(uri: String): Array[TreeViewNode] = {
    if (uri == rootUri) {
      TreeViewNode.sortAlphabetically(toplevels().map(toViewNode).toArray)
    } else {
      val node = fromUri(uri)

      val transitiveChildren = loadSymbols(node.key, node.symbol)
        .filter(i => node.isDescendent(i.symbol))
        .flatMap(i => i :: i.parents)
        .distinctBy(_.symbol)

      val directChildren = transitiveChildren.filter { s =>
        s.symbol.owner == node.symbol && {
          s.kind.isPackage ||
          definitionIndex
            .definition(Symbol(s.symbol))
            .isDefined
        }
      }

      // Auto-expand if there is only a single child node.
      val childHasSiblings = directChildren.length > 1

      val result: Array[TreeViewNode] = directChildren.iterator.map { child =>
        // Infer the title of this node from its symbol kind.
        val displayName = Symbol(child.symbol).displayName
        val label =
          if (child.kind.isPackage) {
            displayName + "/"
          } else if (child.kind.isMethod && !child.isVal && !child.isVar) {
            displayName + "()"
          } else {
            displayName
          }

        // Get the children of this child to determine its collapse state.
        val grandChildren =
          transitiveChildren.filter(_.symbol.owner == child.symbol)
        val collapseState =
          if (!childHasSiblings && grandChildren.nonEmpty)
            MetalsTreeItemCollapseState.expanded
          else if (child.symbol.isPackage)
            MetalsTreeItemCollapseState.collapsed
          else if (grandChildren.nonEmpty)
            MetalsTreeItemCollapseState.collapsed
          else
            MetalsTreeItemCollapseState.none

        val command =
          if (child.kind.isPackage) null
          else MetalsCommand.goto(child.symbol)

        val icon = child.kind match {
          case k.OBJECT | k.PACKAGE_OBJECT => "object"
          case k.TRAIT => "trait"
          case k.CLASS => "class"
          case k.INTERFACE => "interface"
          case k.METHOD | k.MACRO =>
            if (child.properties.isVal) "val"
            else if (child.properties.isVar) "var"
            else "method"
          case k.FIELD =>
            if (child.properties.isEnum) "enum"
            else "field"
          case _ => null
        }

        TreeViewNode(
          viewId,
          nodeUri = node.withSymbol(child.symbol).toUri,
          label = label,
          tooltip = child.symbol,
          collapseState = collapseState,
          command = command,
          icon = icon
        )
      }.toArray

      if (node.symbol.isPackage) {
        TreeViewNode.sortAlphabetically(
          result,
          (a, b) => {
            // Sort package objects at the top.
            -java.lang.Boolean.compare(
              a.label == "package",
              b.label == "package"
            )
          }
        )
      } else {
        // Don't sort type members such as def/val/var in order to preserve
        // definition order in the original source.
        result
      }
    }
  }

  def toUri(
      jar: Key,
      symbol: String = Symbols.RootPackage
  ): NodeUri =
    NodeUri(jar, symbol)

  def fromUri(uri: String): NodeUri = {
    val stripped = uri.stripPrefix(s"$scheme:")
    val separator = stripped.lastIndexOf("!/")
    if (separator < 0) {
      NodeUri(decode(stripped))
    } else {
      val key = decode(stripped.substring(0, separator))
      val symbol = stripped.substring(separator + 2)
      NodeUri(key, symbol)
    }
  }

  def toViewNode(value: Value): TreeViewNode = {
    val uri = toUri(id(value)).toUri
    TreeViewNode(
      viewId,
      uri,
      valueTitle(value),
      tooltip = valueTooltip(value),
      collapseState = MetalsTreeItemCollapseState.collapsed
    )
  }

  /**
   * URI of a tree view node that encodes a SemanticDB symbol and a Key. */
  case class NodeUri(
      key: Key,
      symbol: String = Symbols.RootPackage
  ) {
    override def toString(): String = toUri
    def withSymbol(newSymbol: String): NodeUri =
      copy(symbol = newSymbol)
    val isRoot: Boolean = symbol == Symbols.RootPackage
    def isDescendent(child: String): Boolean =
      if (isRoot) true
      else child.startsWith(symbol)
    def toUri: String = s"$scheme:${encode(key)}!/$symbol"
    def parentChain: List[String] = {
      parent match {
        case None => toUri :: s"$scheme:" :: Nil
        case Some(p) => toUri :: p.parentChain
      }
    }
    def parent: Option[NodeUri] = {
      if (isRoot) None
      else Some(NodeUri(key, symbol.owner))
    }
  }

}
