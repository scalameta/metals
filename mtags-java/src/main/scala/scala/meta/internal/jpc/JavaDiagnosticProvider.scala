package scala.meta.internal.jpc

import scala.util.control.NonFatal

import scala.meta.pc.VirtualFileParams

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

    val javacDiagnostics = for {
      d <- compile.listener.diagnostics.iterator
      // The compiler may report noisy diagnostics from transitive dependencies
      // TODO: we can't rely on the URI here, getName() with originaluri- is better.
      if d.getSource() != null && d.getSource().toUri() == params.uri()
    } yield JavaDiagnostics.toLspDiagnostic(lineMap, params.text(), d)

    javacDiagnostics.toList ++
      missingOverrideDiagnostics(compile) ++
      unusedImportDiagnostics(compile)
  }

  private def missingOverrideDiagnostics(
      compile: JavaSourceCompile
  ): List[l.Diagnostic] =
    try {
      new MissingOverrideDiagnosticProvider(
        compiler,
        compile,
        params.text()
      ).diagnostics()
    } catch {
      case NonFatal(e) =>
        compiler.logger.warn(
          "Failed to compute missing override diagnostics",
          e
        )
        Nil
    }

  private def unusedImportDiagnostics(
      compile: JavaSourceCompile
  ): List[l.Diagnostic] =
    try {
      new UnusedImportDiagnosticProvider(compile).diagnostics()
    } catch {
      case NonFatal(e) =>
        compiler.logger.warn("Failed to compute unused import diagnostics", e)
        Nil
    }
}
