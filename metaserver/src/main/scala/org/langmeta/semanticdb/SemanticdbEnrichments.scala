package org.langmeta.semanticdb

import scala.meta.internal.semanticdb3.SymbolInformation.Kind
import org.langmeta.lsp.SymbolKind

object SemanticdbEnrichments {
  implicit class XtensionIntAsKind(val kind: Int) {
    def isClass: Boolean = Kind.fromValue(kind).isClass
    def isTrait: Boolean = Kind.fromValue(kind).isTrait
    def isObject: Boolean = Kind.fromValue(kind).isObject
    def toSymbolKind: SymbolKind =
      if (isClass) SymbolKind.Class
      else if (isTrait) SymbolKind.Interface
      else SymbolKind.Module
  }
}
