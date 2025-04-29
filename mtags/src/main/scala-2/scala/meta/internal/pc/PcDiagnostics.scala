package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}

import scala.reflect.internal.util.SourceFile
import scala.reflect.internal.Reporter.{ERROR, INFO, WARNING}
import scala.tools.nsc.interactive.Problem

trait PcDiagnostics { compiler: MetalsGlobal =>

  /**
   * Return all problems issued by the presentation compiler for the given file. This method may block
   * waiting for a typechecking run to finish.
   */
  def diagnosticsOf(
      source: SourceFile
  ): Seq[l.Diagnostic] = {
    unitOfFile get source.file match {
      case Some(unit) =>
        metalsAsk[Tree] { response =>
          askLoadedTyped(unit.source, keepLoaded = false, response)
          response.get
        }
        unit.problems.toList.flatMap(toLspDiagnostic)

      case None =>
        pprint.pprintln(
          s"Missing unit for file ${source.file} when retrieving errors. Errors will not be shown in this file. Loaded units are: $unitOfFile"
        )
        Nil
    }
  }

  private def toLspDiagnostic(prob: Problem): Option[l.Diagnostic] = {
    if (prob.pos.isDefined) {
      val severity = prob.severityLevel match {
        case ERROR.id => l.DiagnosticSeverity.Error
        case WARNING.id => l.DiagnosticSeverity.Warning
        case INFO.id => l.DiagnosticSeverity.Information
        case _ => l.DiagnosticSeverity.Hint
      }

      Some(
        new l.Diagnostic(
          prob.pos.toLsp,
          prob.msg,
          severity,
          "scala presentation compiler",
          null
        )
      )
    } else None
  }

}
