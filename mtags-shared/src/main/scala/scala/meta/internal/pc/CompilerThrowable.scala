package scala.meta.internal.pc

import java.{util => ju}

import scala.annotation.tailrec

object CompilerThrowable {

  /**
   * Removes stacktraces that are unrelated to the
   * @param e
   */
  def trimStackTrace(e: Throwable): e.type = {
    val isVisited = ju.Collections.newSetFromMap(
      new java.util.IdentityHashMap[Throwable, java.lang.Boolean]
    )
    @tailrec def loop(ex: Throwable): Unit = {
      isVisited.add(ex)
      val fullStacktrace = ex.getStackTrace
      val stacktrace = {
        val i = fullStacktrace.indexWhere(
          _.getClassName.contains("ScalaPresentationCompiler")
        )
        val hi = if (i < 0) fullStacktrace.length else i + 1
        fullStacktrace.slice(0, hi)
      }
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
