package scala.meta.internal.mtags

import scala.collection.mutable

import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.{semanticdb => s}

case class DocumentToplevels(
    pkg: String,
    toplevels: mutable.ArrayBuffer[String]
)
object DocumentToplevels {
  def empty: DocumentToplevels =
    DocumentToplevels("", mutable.ArrayBuffer.empty)
  def fromDocument(document: s.TextDocument): DocumentToplevels = {
    val toplevels = mutable.ArrayBuffer.empty[String]
    var pkg = Symbols.None
    document.symbols.foreach { info =>
      info.kind match {
        case s.SymbolInformation.Kind.PACKAGE =>
          pkg = info.symbol // The longest package name appears latest
        case s.SymbolInformation.Kind.PACKAGE_OBJECT =>
          toplevels += info.symbol
        case s.SymbolInformation.Kind.CLASS | s.SymbolInformation.Kind.TRAIT |
            s.SymbolInformation.Kind.INTERFACE |
            s.SymbolInformation.Kind.PACKAGE_OBJECT =>
          val (_, owner) = s.Scala.DescriptorParser(info.symbol)
          if (owner.endsWith("/")) {
            toplevels += info.symbol
          }
        case _ =>
      }
    }
    DocumentToplevels(pkg, toplevels)
  }
}
