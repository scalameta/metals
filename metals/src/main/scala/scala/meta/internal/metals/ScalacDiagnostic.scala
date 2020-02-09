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

  object UnimplementedMembers {

    private val regexes = List(
      """since (method|value) (.+) is not defined""".r,
      """it has (one|[0-9]+) unimplemented members""".r
    )

    def matches(v: String): Boolean = {
      regexes.exists(r => r.findFirstMatchIn(v).isDefined)
    }

    def unapply(d: l.Diagnostic): Option[Unit] =
      if (matches(d.getMessage())) Some(()) else None
  }
}
