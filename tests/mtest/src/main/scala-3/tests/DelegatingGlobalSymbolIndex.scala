package tests

import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.mtags
import scala.meta.Dialect

/**
 * Symbol index that delegates all methods to an underlying implementation
 */
class DelegatingGlobalSymbolIndex() extends GlobalSymbolIndex {
  def definitions(symbol: mtags.Symbol): List[SymbolDefinition] = List.empty
  def definition(symbol: mtags.Symbol): Option[SymbolDefinition] = None

  def addSourceFile(
      file: AbsolutePath,
      sourceDirectory: Option[AbsolutePath],
      dialect: Dialect
  ): List[String] = List.empty

  def addSourceJar(
      jar: AbsolutePath,
      dialect: Dialect
  ): List[(String, AbsolutePath)] = List.empty

  def addSourceDirectory(
      dir: AbsolutePath,
      dialect: Dialect
  ): List[(String, AbsolutePath)] = List.empty
}
