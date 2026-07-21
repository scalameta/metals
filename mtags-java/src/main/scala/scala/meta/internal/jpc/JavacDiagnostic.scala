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

  // Example errors:
  // error: incompatible types: java.lang.String cannot be converted to int
  // error: incompatible types: possible lossy conversion from double to int
  class IncompatibleTypes(
      val found: String,
      val required: String
  )
  object IncompatibleTypes {
    private val Code = "compiler.err.prob.found.req"
    private val CannotBeConverted =
      """incompatible types: (.+) cannot be converted to (.+)""".r
    private val PossibleLossyConversion =
      """incompatible types: possible lossy conversion from (.+) to (.+)""".r
    def unapply(d: l.Diagnostic): Option[IncompatibleTypes] = {
      if (!matchesCode(d)) None
      else
        d.getMessageAsString.trim() match {
          case CannotBeConverted(found, required) =>
            Some(new IncompatibleTypes(found, required))
          case PossibleLossyConversion(found, required) =>
            Some(new IncompatibleTypes(found, required))
          case _ => None
        }
    }

    private def matchesCode(d: l.Diagnostic): Boolean =
      Option(d.getCode()).exists(code =>
        code.isLeft() && code.getLeft() == Code
      )
  }

  // Example error:
  // error: Foo is not abstract and does not override abstract method bar() in Baz
  object DoesNotOverrideAbstract {
    private val Code = "compiler.err.does.not.override.abstract"
    private val Message =
      """(?s).* is not abstract and does not override abstract method .*""".r
    def unapply(d: l.Diagnostic): Boolean = {
      val matchesCode =
        d.getCode() != null && d.getCode().isLeft() &&
          d.getCode().getLeft() == Code
      matchesCode || (d.getMessageAsString.trim() match {
        case Message() => true
        case _ => false
      })
    }
  }

}
