package scala.meta.languageserver

import scala.{meta => m}
import langserver.types.SymbolKind
import langserver.types.TextDocumentIdentifier
import langserver.{types => l}

// Extension methods for convenient reuse of data conversions between
// scala.meta._ and language.types._
object ScalametaEnrichments {
  type SymbolKind = Int

  implicit class XtensionTreeLSP(val tree: m.Tree) extends AnyVal {
    import scala.meta._

    def isFunction: Boolean = tree match {
      case d @ Decl.Val(_) => d.decltpe.is[Type.Function]
      case d @ Decl.Var(_) => d.decltpe.is[Type.Function]
      case d @ Defn.Val(_) =>
        d.decltpe.map(_.is[Type.Function]) getOrElse
        d.rhs.is[Term.Function]
      case d @ Defn.Var(_) =>
        d.decltpe.map(_.is[Type.Function]) orElse
        d.rhs.map(_.is[Term.Function]) getOrElse false
      case _ => false
    }

    // we care only about descendants of Member.Type and Member.Term
    def symbolKind: SymbolKind = tree match {
      case f if f.isFunction => SymbolKind.Function
      case Decl.Var(_)  | Defn.Var(_)  => SymbolKind.Variable
      case Decl.Val(_)  | Defn.Val(_)  => SymbolKind.Constant
      case Decl.Def(_)  | Defn.Def(_)  => SymbolKind.Method
      case Decl.Type(_) | Defn.Type(_) => SymbolKind.Field
      case Defn.Macro(_)  => SymbolKind.Constructor
      case Defn.Class(_)  => SymbolKind.Class
      case Defn.Trait(_)  => SymbolKind.Interface
      case Defn.Object(_) => SymbolKind.Namespace
      case Pkg.Object(_)  => SymbolKind.Module
      case Pkg(_)         => SymbolKind.Package
      // case ??? => SymbolKind.Enum
      // case ??? => SymbolKind.String
      // case ??? => SymbolKind.Number
      // case ??? => SymbolKind.Boolean
      // case ??? => SymbolKind.Array
      case _ => SymbolKind.Field
    }
  }
  implicit class XtensionInputLSP(val input: m.Input) extends AnyVal {
    def contents: String = input.asInstanceOf[m.Input.VirtualFile].value
  }
  implicit class XtensionAbsolutePathLSP(val path: m.AbsolutePath)
      extends AnyVal {
    def toLocation(pos: m.Position): l.Location =
      l.Location(path.toLanguageServerUri, pos.toRange)
    def toLanguageServerUri: String = "file:" + path.toString()
  }
  implicit class XtensionPositionRangeLSP(val pos: m.Position) extends AnyVal {
    def location: String =
      s"${pos.input.syntax}:${pos.startLine}:${pos.startColumn}"
    def toRange: l.Range = l.Range(
      l.Position(line = pos.startLine, character = pos.startColumn),
      l.Position(line = pos.endLine, character = pos.endColumn)
    )
  }
  implicit class XtensionSymbolGlobalTerm(val sym: m.Symbol.Global)
      extends AnyVal {
    def toType: m.Symbol.Global = sym match {
      case m.Symbol.Global(owner, m.Signature.Term(name)) =>
        m.Symbol.Global(owner, m.Signature.Type(name))
      case _ => sym
    }
    def toTerm: m.Symbol.Global = sym match {
      case m.Symbol.Global(owner, m.Signature.Type(name)) =>
        m.Symbol.Global(owner, m.Signature.Term(name))
      case _ => sym
    }
  }
}
