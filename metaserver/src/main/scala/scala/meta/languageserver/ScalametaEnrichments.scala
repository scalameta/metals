package scala.meta.languageserver

import scala.{meta => m}

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolKind

// Extension methods for convenient reuse of data conversions between
// scala.meta._ and language.types._
object ScalametaEnrichments {

  implicit class XtensionDenotationLSP(val denotation: m.Denotation)
      extends AnyVal {
    import denotation._
    // copy-pasta from metadoc!
    def symbolKind: SymbolKind = {
      if (isParam || isTypeParam) SymbolKind.Variable // ???
      else if (isVal || isVar) SymbolKind.Variable
      else if (isDef) SymbolKind.Function
      else if (isPrimaryCtor || isSecondaryCtor) SymbolKind.Constructor
      else if (isClass) SymbolKind.Class
      else if (isObject) SymbolKind.Module
      else if (isTrait) SymbolKind.Interface
      else if (isPackage || isPackageObject) SymbolKind.Package
      else if (isType) SymbolKind.Namespace
      else SymbolKind.Variable // ???
    }
  }
  implicit class XtensionInputLSP(val input: m.Input) extends AnyVal {
    def contents: String = input.asInstanceOf[m.Input.VirtualFile].value
  }
  implicit class XtensionAbsolutePathLSP(val path: m.AbsolutePath)
      extends AnyVal {
    def toLocation(pos: m.Position): Location =
      new Location(path.toLanguageServerUri, pos.toRange)
    def toLanguageServerUri: String = "file:" + path.toString()
  }
  implicit class XtensionPositionRangeLSP(val pos: m.Position) extends AnyVal {
    def location: String =
      s"${pos.input.syntax}:${pos.startLine}:${pos.startColumn}"
    def toRange: Range = new Range(
      new Position(pos.startLine, pos.startColumn),
      new Position(pos.endLine, pos.endColumn)
    )
  }
}
