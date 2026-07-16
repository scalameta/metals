package scala.meta.internal.jpc

import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.MANDATORY_WARNING
import javax.tools.Diagnostic.Kind.NOTE
import javax.tools.Diagnostic.Kind.OTHER
import javax.tools.Diagnostic.Kind.WARNING
import javax.tools.JavaFileObject

import com.sun.source.tree.LineMap
import org.eclipse.{lsp4j => l}

object JavaDiagnostics {

  def toLspDiagnostic(
      lineMap: LineMap,
      text: String,
      d: Diagnostic[_ <: JavaFileObject]
  ): l.Diagnostic = {
    val range =
      if (d.getPosition() == Diagnostic.NOPOS)
        new l.Range(new l.Position(0, 0), new l.Position(0, 0))
      else
        Positions.toLspRange(
          lineMap,
          d.getPosition(),
          d.getEndPosition(),
          text
        )
    new l.Diagnostic(
      range,
      d.getMessage(null),
      d.getKind() match {
        case ERROR => l.DiagnosticSeverity.Error
        case WARNING => l.DiagnosticSeverity.Warning
        case OTHER => l.DiagnosticSeverity.Information
        case MANDATORY_WARNING => l.DiagnosticSeverity.Error
        case NOTE => l.DiagnosticSeverity.Hint
        case _ =>
          // Can only happen if it's null or if the compiler adds a new enum
          // member in the future.
          l.DiagnosticSeverity.Error
      },
      "javac",
      d.getCode()
    )
  }
}
