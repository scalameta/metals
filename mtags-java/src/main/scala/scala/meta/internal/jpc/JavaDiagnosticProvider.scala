package scala.meta.internal.jpc

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

    for {
      d <- compile.listener.diagnostics.iterator
      // The compiler may report noisy diagnostics from transitive dependencies
      // TODO: we can't rely on the URI here, getName() with originaluri- is better.
      if d.getSource() != null && d.getSource().toUri() == params.uri()
    } yield JavaDiagnostics.toLspDiagnostic(lineMap, params.text(), d)
  }.toList

}
