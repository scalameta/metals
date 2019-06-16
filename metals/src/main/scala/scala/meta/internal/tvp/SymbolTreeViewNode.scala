package scala.meta.internal.tvp

import scala.meta.internal.semanticdb.Scala._

/**
 * A value and SemanticDB symbol that can be turned into a tree view URI.
 */
case class SymbolTreeViewNode[T](
    scheme: String,
    value: T,
    encode: T => String,
    symbol: String = Symbols.RootPackage
) {
  override def toString(): String = toUri
  def withSymbol(newSymbol: String): SymbolTreeViewNode[T] =
    copy(symbol = newSymbol)
  val isRoot = symbol == Symbols.RootPackage
  def isDescendent(child: String): Boolean =
    if (isRoot) true
    else child.startsWith(symbol)
  def toUri: String = s"$scheme:${encode(value)}!/$symbol"
  def parent: Option[SymbolTreeViewNode[T]] = {
    if (isRoot) None
    else Some(SymbolTreeViewNode(scheme, value, encode, symbol.owner))
  }
}

object SymbolTreeViewNode {
  def fromUri[T](
      scheme: String,
      uri: String,
      decode: String => T,
      encode: T => String
  ): SymbolTreeViewNode[T] = {
    val stripped = uri.stripPrefix(s"$scheme:")
    val separator = stripped.lastIndexOf("!/")
    if (separator < 0) {
      SymbolTreeViewNode(scheme, decode(stripped), encode)
    } else {
      val value = decode(stripped.substring(0, separator))
      val symbol = stripped.substring(separator + 2)
      SymbolTreeViewNode(scheme, value, encode, symbol)
    }
  }
}
