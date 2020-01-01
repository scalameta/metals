package scala.meta.internal.worksheets

import org.eclipse.{lsp4j => l}
import mdoc.{interfaces => i}

object MdocEnrichments {

  implicit class XtensionRangePosition(p: i.RangePosition) {
    def isNone: Boolean =
      p.startLine() < 0 &&
        p.startColumn() < 0 &&
        p.endColumn() < 0 &&
        p.endLine() < 0
    def toLsp: l.Range = {
      new l.Range(
        new l.Position(
          p.startLine(),
          p.startColumn()
        ),
        new l.Position(
          p.endLine(),
          p.endColumn()
        )
      )
    }
  }

  implicit class XtensionDiagnostic(d: i.Diagnostic) {
    def toLsp: l.Diagnostic = {
      new l.Diagnostic(
        d.position().toLsp,
        d.message(),
        d.severity() match {
          case i.DiagnosticSeverity.Info => l.DiagnosticSeverity.Information
          case i.DiagnosticSeverity.Warning => l.DiagnosticSeverity.Warning
          case i.DiagnosticSeverity.Error => l.DiagnosticSeverity.Error
          case _ => l.DiagnosticSeverity.Error
        },
        "mdoc"
      )
    }
  }

}
