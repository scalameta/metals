package tests

import scala.meta.internal.mtags
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.io.AbsolutePath

/**
 * Symbol index that delegates all methods to an underlying implementation */
class DelegatingGlobalSymbolIndex(
    var underlying: GlobalSymbolIndex = OnDemandSymbolIndex()
) extends GlobalSymbolIndex {
  def definition(symbol: mtags.Symbol): Option[SymbolDefinition] = {
    underlying.definition(symbol)
  }
  def addSourceFile(
      file: AbsolutePath,
      sourceDirectory: Option[AbsolutePath]
  ): Unit = {
    underlying.addSourceFile(file, sourceDirectory)
  }
  def addSourceJar(jar: AbsolutePath): Unit = {
    underlying.addSourceJar(jar)
  }
  def addSourceDirectory(dir: AbsolutePath): Unit = {
    underlying.addSourceDirectory(dir)
  }
}
