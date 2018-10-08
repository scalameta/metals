package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath
import scala.meta.internal.{semanticdb => s}

trait SymbolIndex {
  def addSemanticdb(file: AbsolutePath): Unit
  def addSourceJar(jar: AbsolutePath): Unit
  def addSourceFile(file: AbsolutePath): Unit
  def symbol(path: AbsolutePath, range: s.Range): Option[String]
  def definition(symbol: String): Option[SymbolDefinition]
}

case class SymbolDefinition(
    querySymbol: String,
    definitionSymbol: String,
    path: AbsolutePath
)
