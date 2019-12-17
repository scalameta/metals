package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}

object ScalacDiagnostic {
  object SymbolNotFound {
    private val regex = """not found: (value|type) (\w+)""".r
    def unapply(d: l.Diagnostic): Option[String] = d.getMessage() match {
      case regex(_, name) => Some(name)
      case _ => None
    }
  }
}
