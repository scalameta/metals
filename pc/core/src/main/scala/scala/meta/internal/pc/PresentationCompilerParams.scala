package scala.meta.internal.pc

import scala.meta.pc.SymbolIndexer
import scala.meta.pc.SymbolSearch

case class PresentationCompilerParams(
    search: SymbolSearch,
    indexer: SymbolIndexer
)
