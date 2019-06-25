package scala.meta.internal.metals

import java.util
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.SymbolDocumentation

case class MetalsSymbolDocumentation(
    symbol: String,
    displayName: String,
    docstring: String,
    defaultValue: String = "",
    typeParameters: util.List[SymbolDocumentation] = Nil.asJava,
    parameters: util.List[SymbolDocumentation] = Nil.asJava
) extends SymbolDocumentation
