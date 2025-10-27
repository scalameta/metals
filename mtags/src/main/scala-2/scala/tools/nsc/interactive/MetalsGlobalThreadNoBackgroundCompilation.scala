package scala.tools.nsc.interactive

import scala.meta.internal.pc.InterruptException

/**
 * A version of [[MetalsGlobalThread]] that does not do backgroundCompilation. In case the user
 * wants to see error diagnostics from the presentation compiler, we run compilation explicitly
 * on the CompilerJobQueue.
 */
final class MetalsGlobalThreadNoBackgroundCompilation(
    var compiler: Global,
    name: String = ""
) extends Thread(
      "Scala Presentation Compiler w/o backgroundCompile[" + name + "]"
    ) {

  /**
   * The presentation compiler loop.
   */
  override def run(): Unit = {
    compiler.debugLog("starting new runner thread")
    System.err.println(
      s"Starting up PC for $name. Sourcepath: ${if (compiler.settings.sourcepath.value.isEmpty) "empty" else "non-empty"}"
    )
    while (compiler ne null) try {
      compiler.checkNoResponsesOutstanding()
      compiler.log.logreplay(
        "wait for more work", {
          compiler.scheduler.waitForMoreWork(); true
        }
      )
      compiler.pollForWork(compiler.NoPosition)

      // the compiler thread does not do type checking, just servicing other requests as they come in
      // in particular, askReload which adds sources to be managed
    } catch {
      case ShutdownReq =>
        compiler.debugLog("exiting presentation compiler")
        compiler.log.close()

        compiler.debugLog(s"Shutting down PC for $name")
        // make sure we don't keep around stale instances
        compiler = null
      case ex: Throwable =>
        compiler.log.flush()

        ex match {
          // + scalac deviation
          case InterruptException() =>
            Thread.interrupted()
          case _: ThreadDeath =>
            compiler = null
          // - scalac deviation
          case _: FreshRunReq =>
            compiler.debugLog(
              "fresh run req caught outside presentation compiler loop; ignored"
            ) // This shouldn't be reported
          case _: Global#ValidateException => // This will have been reported elsewhere
            compiler.debugLog(
              "validate exception caught outside presentation compiler loop; ignored"
            )
          case _ =>
            ex.printStackTrace(); compiler.informIDE("Fatal Error: " + ex)
        }
    }
  }
}
