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
    val previous = compileRunner
    if (previous.isAlive) {
      try {
        this.askShutdown()
        var interrupted = false
        while (previous.isAlive) {
          /* Join makes sure that all thread writes are visible to the current thread,
           * otherwise it's possible that we create new WorkScheduler, which is then
           * replaced by NoWorkScheduler from previous.
           *
           * Later when we want to shutdown the compiler, nothing will happen
           * because NoWorkScheduler will not do anything.
           */
          try previous.join()
          catch {
            case _: InterruptedException =>
              interrupted = true
          }
        }
        if (interrupted) Thread.currentThread.interrupt()
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
