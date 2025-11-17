package scala.meta.internal.jpc

import java.time.Duration

import scala.meta.pc.ProgressBars

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TaskEvent
import com.sun.source.util.TaskListener
import com.sun.tools.javac.api.JavacTaskImpl
import org.slf4j.LoggerFactory

/**
 * The compilation of a single source file.
 */
case class JavaSourceCompile(
    task: JavacTask,
    listener: JavaCompileTaskListener,
    cu: CompilationUnitTree,
    rest: collection.Seq[CompilationUnitTree],
    progressBars: ProgressBars
) {
  def allCompilationUnits: collection.Seq[CompilationUnitTree] = cu +: rest
  private var updateMessage: String => Unit = _ => ()
  task.addTaskListener(new TaskListener {
    override def started(task: TaskEvent): Unit = {
      updateMessage(task.getSourceFile.getName.split('/').last)
    }
  })

  private val logger = LoggerFactory.getLogger(classOf[JavaSourceCompile])

  private var analyzed = false
  def withAnalyzePhase(): this.type = synchronized {
    if (analyzed) {
      return this
    }
    val progressBar =
      progressBars.start(
        new ProgressBars.StartProgressBarParams("Analyzing")
          .withProgress(true)
          .withTimeout(
            // Analyze phase should typically be faster than 15 seconds, but if it takes longer
            // then we get a log warning that we can investigate.
            new ProgressBars.ProgressTimeout(
              Duration.ofSeconds(15),
              () => {
                logger.warn("Analyze phase timed out after 15 seconds")
              }
            )
          )
      )
    val shortName = cu.getSourceFile.getName.split('/').last
    updateMessage = message => progressBar.updateMessage(message)
    updateMessage(shortName)
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
      progressBars.end(progressBar)
    }
    analyzed = true
    this
  }

}
