package scala.meta.internal.tvp

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.mtags.GlobalSymbolIndex

/**
 * A tree view that exposes a package explorer to browse symbols such as classes, traits and methods.
 *
 * @param A The metadata used to render the toplevel nodes such as jar files or
 *          a Scala build target (with scala version, classpath, ...).
 * @param B The identifier key that is persisted into the URI of tree view nodes,
 *          such as a jar file URI or a build target URI.
 */
class ClasspathTreeView[A, B](
    definitionIndex: GlobalSymbolIndex,
    viewId: String,
    scheme: String,
    title: String,
    id: A => B,
    encode: B => String,
    decode: String => B,
    valueTitle: A => String,
    valueTooltip: A => String,
    toplevels: () => Iterator[A],
    loadSymbols: (B, String) => Iterator[TreeViewSymbolInformation]
) {
  val rootUri = scheme + ":"
  def root = TreeViewNode(
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
      val descendents = loadSymbols(node.value, node.symbol)
        .filter(i => node.isDescendent(i.symbol))
        .flatMap(i => i :: i.parents)
        .distinctBy(_.symbol)
      val children = descendents.filter { s =>
        s.symbol.owner == node.symbol && {
          s.kind.isPackage ||
          definitionIndex
            .definition(Symbol(s.symbol))
            .isDefined
        }
      }
      val hasSiblings = children.length > 1
      val result = children.iterator.map { info =>
        val grandChildren =
          descendents.filter(_.symbol.owner == info.symbol)
        val childNode = node.withSymbol(info.symbol)
        val displayName = Symbol(info.symbol).displayName
        val label =
          if (info.kind.isPackage) {
            displayName + "/"
          } else if (info.kind.isMethod && !info.isVal && !info.isVar) {
            displayName + "()"
          } else {
            displayName
          }
        val collapseState =
          if (!hasSiblings && grandChildren.nonEmpty)
            MetalsTreeItemCollapseState.expanded
          else if (info.symbol.isPackage)
            MetalsTreeItemCollapseState.collapsed
          else if (grandChildren.nonEmpty)
            MetalsTreeItemCollapseState.collapsed
          else
            MetalsTreeItemCollapseState.none
        TreeViewNode(
          "build",
          childNode.toUri,
          label = label,
          tooltip = info.symbol,
          collapseState = collapseState,
          command =
            if (info.kind.isPackage) null
            else MetalsCommand.goto(info.symbol),
          icon = info.kind match {
            case k.OBJECT | k.PACKAGE_OBJECT => "object"
            case k.TRAIT => "trait"
            case k.CLASS => "class"
            case k.INTERFACE => "interface"
            case k.METHOD | k.MACRO =>
              if (info.properties.isVal) "val"
              else if (info.properties.isVar) "var"
              else "method"
            case k.FIELD =>
              if (info.properties.isEnum) "enum"
              else "field"
            case _ => null
          }
        )
      }.toArray
      if (node.symbol.isPackage) {
        TreeViewNode.sortAlphabetically(
          result,
          (a, b) => {
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
      jar: B,
      symbol: String = Symbols.RootPackage
  ): SymbolTreeViewNode[B] =
    SymbolTreeViewNode[B](scheme, jar, encode, symbol)
  def fromUri(uri: String): SymbolTreeViewNode[B] =
    SymbolTreeViewNode.fromUri[B](scheme, uri, decode, encode)

  def toViewNode(value: A): TreeViewNode = {
    val uri = toUri(id(value)).toUri
    TreeViewNode(
      viewId,
      uri,
      valueTitle(value),
      tooltip = valueTooltip(value),
      collapseState = MetalsTreeItemCollapseState.collapsed
    )
  }

}
