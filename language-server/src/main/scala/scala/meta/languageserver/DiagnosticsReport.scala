package scala.meta.languageserver

import langserver.types.Diagnostic
import org.langmeta.io.RelativePath

/** Message to Connection.reportDiagnostics */
case class DiagnosticsReport(path: RelativePath, diagnostics: Seq[Diagnostic])
