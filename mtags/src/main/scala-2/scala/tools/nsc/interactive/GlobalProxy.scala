package scala.tools.nsc.interactive

import scala.tools.nsc.util.WorkScheduler
import scala.util.control.NonFatal

import scala.meta.internal.pc.MetalsGlobal

trait GlobalProxy { this: MetalsGlobal =>
  def presentationCompilerThread: Thread = this.compileRunner
  def hijackPresentationCompilerThread(backgroundCompilation: Boolean): Unit =
    newRunnerThread(backgroundCompilation)

  /**
   * Forwarder to package private `typeMembers` method.
   */
  def metalsTypeMembers(pos: Position): List[Member] = {
    metalsAsk[List[Member]](r => getTypeCompletion(pos, r))
  }

  def metalsScopeMembers(pos: Position): List[Member] = {
    metalsAsk[List[Member]](r => getScopeCompletion(pos, r))
  }

  /**
   * Run the given function on a freshly created response, **on the current thread**.
   */
  def metalsAsk[T](fn: Response[T] => Unit): T = {
    val r = new Response[T]
    fn(r)
    r.get match {
      case Left(value) =>
        value
      case Right(value) =>
        throw value
    }
  }

  /**
   * Shuts down the default presentation compiler thread and replaces it with a custom implementation.
   */
  private def newRunnerThread(backgroundCompilation: Boolean): Thread = {
    if (compileRunner.isAlive) {
      try {
        this.askShutdown()
        while (compileRunner.isAlive) Thread.sleep(0)
      } catch {
        case NonFatal(e) =>
          logger.info(
            "unexpected error shutting down presentation compiler thread",
            e
          )
      }
    }
    this.scheduler = new WorkScheduler
    compileRunner = if (backgroundCompilation) {
      new MetalsGlobalThread(this, s"Metals/${buildTargetIdentifier}")
    } else
      new MetalsGlobalThreadNoBackgroundCompilation(
        this,
        s"Metals/${buildTargetIdentifier}"
      )
    compileRunner.setDaemon(true)
    compileRunner.start()
    compileRunner
  }
}
