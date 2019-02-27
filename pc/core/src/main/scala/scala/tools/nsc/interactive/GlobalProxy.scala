package scala.tools.nsc.interactive

import java.util.logging.Level
import scala.meta.internal.pc.MetalsGlobal
import scala.util.control.NonFatal

trait GlobalProxy { this: MetalsGlobal =>
  def presentationCompilerThread: Thread = this.compileRunner
  def hijackPresentationCompilerThread(): Unit = newRunnerThread()

  var threadId = 0

  /**
   * Shuts down the default presentation compiler thread and replaces it with a custom implementation.
   */
  private def newRunnerThread(): Thread = {
    threadId += 1
    if (compileRunner.isAlive) {
      try {
        val re = askForResponse(() => throw ShutdownReq)
        re.get
      } catch {
        case NonFatal(e) =>
          metalsLogger.log(
            Level.INFO,
            "unexpected error shutting down presentation compiler thread",
            e
          )
      }
    }
    compileRunner = new MetalsGlobalThread(this, "Metals")
    compileRunner.setDaemon(true)
    compileRunner.start()
    compileRunner
  }
}
