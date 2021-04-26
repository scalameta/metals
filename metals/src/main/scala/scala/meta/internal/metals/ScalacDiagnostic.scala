package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}

object ScalacDiagnostic {

  object SymbolNotFound {
    private val regex = """(n|N)ot found: (value|type)?\s?(\w+)""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex(_, _, name) => Some(name)
        case _ => None
      }
  }
  object MissingImplementation {
    // https://github.com/scala/scala/blob/fd69ef805d4ba217f3495c106f9c698094682ae8/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L547
    private val regex = """(?s).*needs to be abstract.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }
  object ObjectCreationImpossible {
    // https://github.com/scala/scala/blob/4c0f49c7de6ba48f2b0ae59e64ea94fabd82b4a7/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L566
    private val regex = """(?s).*object creation impossible.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }

  object UnusedImport {
    private val regex = """(?i)Unused import""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }
}
