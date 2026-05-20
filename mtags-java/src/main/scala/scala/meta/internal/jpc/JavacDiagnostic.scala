package scala.meta.internal.jpc

import scala.meta.internal.mtags.CommonMtagsEnrichments._

import org.eclipse.{lsp4j => l}

object JavacDiagnostic {

// Example error:
// error: cannot find symbol
// symbol: (class|interface|enum) Foo
// location: class Bar

  class CannotFindSymbol(
      val code: String,
      val kind: String,
      val symbol: String,
      val location: String
  ) {
    def isCantResolve: Boolean =
      code.startsWith("compiler.err.cant.resolve")
    def isCantApplySymbol: Boolean =
      code == "compiler.err.cant.apply.symbol"
  }
  object CannotFindSymbol {
    private val WithLocation =
      """cannot find symbol(?m)\s+symbol:\s+([^ ]+)\s+(.*)(?m)\s+location:\s+(.*)""".r
    private val WithoutLocation =
      """cannot find symbol(?m)\s+symbol:\s+([^ ]+)\s+(.*)""".r
    def unapply(d: l.Diagnostic): Option[CannotFindSymbol] =
      d.getMessageAsString.trim() match {
        case WithLocation(kind, symbol, location) if d.getCode().isLeft() =>
          Some(
            new CannotFindSymbol(d.getCode().getLeft(), kind, symbol, location)
          )
        case WithoutLocation(kind, symbol) if d.getCode().isLeft() =>
          Some(
            new CannotFindSymbol(d.getCode().getLeft(), kind, symbol, "")
          )
        case _ => None
      }
  }

}
