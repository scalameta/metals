package scala.meta.languageserver.compiler

import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global
import com.typesafe.scalalogging.LazyLogging

object CompilerUtils extends LazyLogging {
  def safeCompletionsAt[G <: Global](
      global: Global,
      position: Position
  ): List[global.CompletionResult#M] = {
    try {
      global.completionsAt(position).matchingResults().distinct
    } catch {
      case e: StringIndexOutOfBoundsException =>
        // NOTE(olafur) Let's log this for now while we are still learning more
        // about the PC. However, I haven't been able to avoid this exception
        // in some cases so I suspect it's here to stay until we fix it upstream.
        val stackTrace = e.getStackTrace.lift(4).map(_.toString).getOrElse("")
        logger.warn("Expected StringIndexOutOfBounds at " + stackTrace)
        Nil
    }
  }

}
