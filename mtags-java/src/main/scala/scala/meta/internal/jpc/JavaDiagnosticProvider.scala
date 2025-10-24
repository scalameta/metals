package scala.meta.internal.jpc

import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.MANDATORY_WARNING
import javax.tools.Diagnostic.Kind.NOTE
import javax.tools.Diagnostic.Kind.OTHER
import javax.tools.Diagnostic.Kind.WARNING
import javax.tools.JavaFileObject

import scala.meta.pc.VirtualFileParams

import com.sun.source.tree.LineMap
import org.eclipse.{lsp4j => l}
class JavaDiagnosticProvider(
    compiler: JavaMetalsCompiler,
    params: VirtualFileParams
) {

  def diagnostics(): List[l.Diagnostic] = {
    if (params.text().isEmpty) {
      compiler.logger.error(
        s"Expected non-empty text for path ${params.uri()}"
      )
      return Nil
    }
    params.checkCanceled()
    val compile = compiler.compileTask(params)
    val cu = compile.cu
    params.checkCanceled()
    val lineMap = cu.getLineMap()
    // Run full type-checking
    compile.task.analyze()
    params.checkCanceled()

    for {
      d <- compile.listener.diagnostics.iterator
      // The compiler may report noisy diagnostics from transitive dependencies
      // TODO: we can't rely on the URI here, getName() with originaluri- is better.
      if d.getSource() != null && d.getSource().toUri() == params.uri()
    } yield toLspDiagnostic(lineMap, d)
  }.toList

  private def toLspDiagnostic(
      lineMap: LineMap,
      d: Diagnostic[_ <: JavaFileObject]
  ): l.Diagnostic = {
    new l.Diagnostic(
      Positions.toLspRange(
        lineMap,
        d.getPosition(),
        d.getEndPosition(),
        params.text()
      ),
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
