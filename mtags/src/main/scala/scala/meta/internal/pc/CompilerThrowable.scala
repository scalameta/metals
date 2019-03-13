package scala.meta.internal.pc

import java.util.Collections
import scala.annotation.tailrec

object CompilerThrowable {

  /**
   * Removes stacktraces that are unrelated to the
   * @param e
   */
  def trimStackTrace(e: Throwable): e.type = {
    val isVisited = Collections.newSetFromMap(
      new java.util.IdentityHashMap[Throwable, java.lang.Boolean]
    )
    @tailrec def loop(ex: Throwable): Unit = {
      isVisited.add(ex)
      val stacktrace = ex.getStackTrace.takeWhile(
        !_.getClassName.contains("ScalaPresentationCompiler")
      )
      ex.setStackTrace(stacktrace)
      // avoid infinite loop when traversing exceptions cyclic dependencies between causes.
      if (e.getCause != null && !isVisited.contains(e.getCause)) {
        loop(e.getCause)
      }
    }
    loop(e)
    e
  }
}
