package scala.meta.internal.pc

import scala.tools.nsc.reporters.StoreReporter

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

    val storeReporter = reporter.asInstanceOf[StoreReporter]
    storeReporter.infos.map { info =>
      val severity =
        info.severity match {
          case storeReporter.ERROR => DiagnosticSeverity.Error
          case storeReporter.WARNING => DiagnosticSeverity.Warning
          case storeReporter.INFO => DiagnosticSeverity.Information
          // to avoid warning
          case _ => DiagnosticSeverity.Information
        }

      new Diagnostic(
        info.pos.toLsp,
        info.msg,
        severity,
        "pc"
      )
    }.toList
  }

}
