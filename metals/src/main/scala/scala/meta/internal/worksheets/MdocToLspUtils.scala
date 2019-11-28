package scala.meta.internal.worksheets

import org.eclipse.{lsp4j => l}
import mdoc.{interfaces => i}

object MdocToLspUtils {

  def toLsp(p: i.RangePosition): l.Range = {
    new l.Range(
      new l.Position(
        p.startLine(),
        p.startColumn()
      ),
      new l.Position(
        p.endLine(),
        p.endColumn()
      )
    ),
  }

  def toLsp(d: i.Diagnostic): l.Diagnostic = {
    new l.Diagnostic(
      toLsp(d.position()),
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
