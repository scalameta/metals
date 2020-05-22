package scala.meta.internal.worksheets

import mdoc.interfaces.EvaluatedWorksheetStatement
import mdoc.{interfaces => i}
import org.eclipse.{lsp4j => l}

object MdocEnrichments {

  implicit class XtensionRangePosition(p: i.RangePosition) {
    def isNone: Boolean =
      p.startLine() < 0 &&
        p.startColumn() < 0 &&
        p.endColumn() < 0 &&
        p.endLine() < 0
    def toLsp: l.Range = {
      if (isNone) {
        // Don't construct invalid positions with negative values
        new l.Range(
          new l.Position(0, 0),
          new l.Position(0, 0)
        )
      } else {
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

  /**
   * Determines whether or not an evaluated worksheet statement summary is
   * complete or not. If it is complete, it just returns the summary, otherwise
   * it will chop off the last 3 chars and replace them with … to signify
   * continuation
   */
  def truncatify(statement: EvaluatedWorksheetStatement): String = {
    if (statement.isSummaryComplete()) statement.summary()
    else statement.summary() + "…"
  }

}
