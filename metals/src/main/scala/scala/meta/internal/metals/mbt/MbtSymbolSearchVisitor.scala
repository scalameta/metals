package scala.meta.internal.metals.mbt

import scala.meta.internal.{semanticdb => s}

@FunctionalInterface
abstract class MbtSymbolSearchVisitor {
  def onMatch(
      doc: s.TextDocument,
      info: s.SymbolInformation,
      symbol: s.SymbolOccurrence,
  ): MbtSymbolSearchResult
}
abstract class MbtSymbolSearchResult
case object Stop extends MbtSymbolSearchResult
case object Continue extends MbtSymbolSearchResult
