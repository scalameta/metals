package scala.meta.internal.metals

import java.util
import java.util.Collections

import scala.meta.pc.SymbolDocumentation

case class ProxySymbolDocumentation(orginalSymbol: String)
    extends SymbolDocumentation {
  override def symbol(): String = ""
  override def displayName(): String = ""
  override def docstring(): String = ""
  override def defaultValue(): String = ""
  override def typeParameters(): util.List[SymbolDocumentation] =
    Collections.emptyList()
  override def parameters(): util.List[SymbolDocumentation] =
    Collections.emptyList()
}
