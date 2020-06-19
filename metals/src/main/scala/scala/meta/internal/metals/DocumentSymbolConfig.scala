package scala.meta.internal.metals

import scala.meta.internal.metals.DocumentSymbolConfig._

final case class DocumentSymbolConfig(value: String) {
  def isSymbolInformation: Boolean = this == symbolInformation
  def isDocumentSymbol: Boolean = this == documentSymbol
}
object DocumentSymbolConfig {
  def symbolInformation: DocumentSymbolConfig =
    new DocumentSymbolConfig("symbol-information")
  def documentSymbol: DocumentSymbolConfig =
    new DocumentSymbolConfig("document-symbol")
  def default =
    new DocumentSymbolConfig(
      System.getProperty("metals.document-symbol", documentSymbol.value)
    )
}
