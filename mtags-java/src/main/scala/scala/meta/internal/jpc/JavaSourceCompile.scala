package scala.meta.internal.jpc

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask

/**
 * The compilation of a single source file.
 */
case class JavaSourceCompile(
    task: JavacTask,
    listener: JavaCompileTaskListener,
    cu: CompilationUnitTree
) {
  def withAnalyzePhase(): this.type = {
    task.analyze()
    this
  }
}
