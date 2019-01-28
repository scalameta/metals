package scala.meta.internal.pc

import scala.meta.pc.SymbolIndexer
import scala.meta.pc.SymbolVisitor

object EmptySymbolIndexer extends SymbolIndexer {
  override def visit(symbol: String, visitor: SymbolVisitor): Unit = {}
}
