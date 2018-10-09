package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath

/**
 *
 */
trait GlobalSymbolIndex {

  def addSourceJar(jar: AbsolutePath): Unit
  def addSourceFile(file: AbsolutePath): Unit

  def definition(symbol: String): Option[SymbolDefinition]

}

case class SymbolDefinition(
    querySymbol: String,
    definitionSymbol: String,
    path: AbsolutePath
)
