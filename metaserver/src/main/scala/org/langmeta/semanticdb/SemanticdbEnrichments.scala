package org.langmeta.semanticdb

import langserver.types.SymbolKind

object SemanticdbEnrichments {
  implicit class XtensionLongAsFlags(val flags: Long) extends HasFlags {
    def hasOneOfFlags(flags: Long): Boolean =
      (this.flags & flags) != 0L
    def toSymbolKind: SymbolKind =
      if (isClass) SymbolKind.Class
      else if (isTrait) SymbolKind.Interface
      else SymbolKind.Module
  }
}
