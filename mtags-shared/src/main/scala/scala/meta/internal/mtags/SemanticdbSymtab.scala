package scala.meta.internal.mtags

import java.util.HashMap

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.jsemanticdb.Semanticdb

class SemanticdbSymtab(
    private val symtab: collection.Map[String, Semanticdb.SymbolInformation]
) {
  def info(symbol: String): Semanticdb.SymbolInformation = {
    symtab.get(symbol) match {
      case Some(value) =>
        value
      case None =>
        // Just return an empty symbol
        Semanticdb.SymbolInformation.newBuilder().setSymbol(symbol).build()
    }
  }
}
object SemanticdbSymtab {
  def fromDoc(
      doc: Semanticdb.TextDocument
  ): SemanticdbSymtab = {
    val symtab = new HashMap[String, Semanticdb.SymbolInformation]().asScala
    doc.getSymbolsList().asScala.foreach { sym =>
      symtab.put(sym.getSymbol(), sym)
    }
    new SemanticdbSymtab(symtab)
  }
}
