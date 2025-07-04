package scala.meta.internal.pc

import scala.reflect.internal.Reporter

import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

object DiagnosticsProvider {
  def getDiagnostics(
      compiler: MetalsGlobal,
      params: VirtualFileParams
  ): List[Diagnostic] = {
    import compiler._
    val unit = addCompilationUnit(
      params.text(),
      params.uri().toString(),
      cursor = None
    )

    typeCheck(unit)

    unit.problems.toList.flatMap { prob =>
      if (prob.pos.isDefined) {
        val severity = prob.severityLevel match {
          case Reporter.ERROR.id => DiagnosticSeverity.Error
          case Reporter.WARNING.id => DiagnosticSeverity.Warning
          case Reporter.INFO.id => DiagnosticSeverity.Information
          case _ => DiagnosticSeverity.Hint
        }

        Some(
          new Diagnostic(
            prob.pos.toLsp,
            prob.msg,
            severity,
            "pc"
          )
        )
      } else None
    }
  }

}
