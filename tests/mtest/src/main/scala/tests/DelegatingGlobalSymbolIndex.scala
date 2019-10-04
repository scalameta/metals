package tests

import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.mtags.Symbol

/** Symbol index that delegates all methods to an underlying implementation */
class DelegatingGlobalSymbolIndex(var underlying: GlobalSymbolIndex)
    extends GlobalSymbolIndex {
  def definition(symbol: Symbol): Option[SymbolDefinition] = {
    underlying.definition(symbol)
  }
  def addSourceFile(
      file: AbsolutePath,
      sourceDirectory: Option[AbsolutePath]
  ): Unit = {
    underlying.addSourceFile(file, sourceDirectory)
  }
  def addSourceJar(jar: AbsolutePath): scala.util.Try[Unit] = {
    underlying.addSourceJar(jar)
  }
}
