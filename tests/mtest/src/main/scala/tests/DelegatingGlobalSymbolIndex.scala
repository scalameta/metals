package tests

import scala.meta.Dialect
import scala.meta.internal.mtags
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.io.AbsolutePath

/**
 * Symbol index that delegates all methods to an underlying implementation
 */
class DelegatingGlobalSymbolIndex(
    var underlying: OnDemandSymbolIndex =
      OnDemandSymbolIndex.empty(mtags = () => mtags.Mtags.testingSingleton)
) extends GlobalSymbolIndex {

  def definitions(symbol: mtags.Symbol): List[SymbolDefinition] =
    underlying.definitions(symbol: mtags.Symbol)

  def definition(symbol: mtags.Symbol): Option[SymbolDefinition] = {
    underlying.definition(symbol)
  }
  def addSourceFile(
      file: AbsolutePath,
      sourceDirectory: Option[AbsolutePath],
      dialect: Dialect
  ): Option[mtags.IndexingResult] = {
    underlying.addSourceFile(file, sourceDirectory, dialect)
  }
  def addSourceJar(
      jar: AbsolutePath,
      dialect: Dialect
  ): List[mtags.IndexingResult] = {
    underlying.addSourceJar(jar, dialect)
  }

  def addSourceDirectory(
      dir: AbsolutePath,
      dialect: Dialect
  ): List[mtags.IndexingResult] = {
    underlying.addSourceDirectory(dir, dialect)
  }
}
