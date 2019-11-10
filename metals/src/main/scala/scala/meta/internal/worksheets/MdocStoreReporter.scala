package scala.meta.internal.worksheets

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import mdoc.internal.io.ConsoleReporter
import scala.collection.mutable
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MdocStoreReporter() extends ConsoleReporter(System.out) {
  val diagnostics: mutable.LinkedHashSet[Diagnostic] =
    mutable.LinkedHashSet.empty[Diagnostic]

  override def reset(): Unit = diagnostics.clear()

  override def warningCount: Int =
    diagnostics.count(_.getSeverity() == DiagnosticSeverity.Warning)
  override def errorCount: Int =
    diagnostics.count(_.getSeverity() == DiagnosticSeverity.Error)
  override def hasErrors: Boolean = errorCount > 0
  override def hasWarnings: Boolean = warningCount > 0

  override def warning(pos: Position, msg: String): Unit = {
    diagnostics += new Diagnostic(
      pos.toLSP,
      msg,
      DiagnosticSeverity.Warning,
      "mdoc"
    )
    super.warning(pos, msg)
  }
  override def error(pos: Position, throwable: Throwable): Unit = {
    val out = new ByteArrayOutputStream()
    throwable.printStackTrace(new PrintStream(out))
    diagnostics += new Diagnostic(
      pos.toLSP,
      out.toString(),
      DiagnosticSeverity.Error,
      "mdoc"
    )
  }
  override def error(pos: Position, msg: String): Unit = {
    diagnostics += new Diagnostic(
      pos.toLSP,
      msg,
      DiagnosticSeverity.Error,
      "mdoc"
    )
    super.error(pos, msg)
  }
}
