package scala.meta.internal.jpc

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.tools.javac.api.JavacTaskImpl

/**
 * The compilation of a single source file.
 */
case class JavaSourceCompile(
    task: JavacTask,
    listener: JavaCompileTaskListener,
    cu: CompilationUnitTree,
    rest: collection.Seq[CompilationUnitTree]
) {
  def allCompilationUnits: collection.Seq[CompilationUnitTree] = cu +: rest

  private var analyzed = false
  def withAnalyzePhase(): this.type = synchronized {
    if (analyzed) {
      return this
    }
    try {
      task match {
        case task: JavacTaskImpl =>
          // This is a public method but it's not part of the `JavacTask` API.
          // The benefit of this method is that it doesn't hide useful details
          // from thrown exceptions.
          task.analyze(null)
        case _ =>
          // Should not happen since it's unclear what other implementations of
          // `JavacTask` exist. However, just in case, we fallback to it here even
          // if it potentially means hiding useful details from thrown exceptions.
          task.analyze()
      }
    } finally {
      analyzeCompleted()
    }
    analyzed = true
    this
  }
  def analyzeCompleted(): Unit = {
    // No-op right now, but will soon influence progress bars in the UI
    ()
  }
}
