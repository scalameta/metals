package scala.tools.nsc.interactive

import java.util.logging.Level

import scala.tools.nsc.util.WorkScheduler
import scala.util.control.NonFatal

import scala.meta.internal.pc.MetalsGlobal

trait GlobalProxy { this: MetalsGlobal =>
  def presentationCompilerThread: Thread = this.compileRunner
  def hijackPresentationCompilerThread(): Unit = newRunnerThread()

  /**
   * Forwarder to package private `typeMembers` method.
   */
  def metalsTypeMembers(pos: Position): List[Member] = {
    metalsAsk[List[Member]](r => getTypeCompletion(pos, r))
  }

  def metalsScopeMembers(pos: Position): List[Member] = {
    metalsAsk[List[Member]](r => getScopeCompletion(pos, r))
  }

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
  private def newRunnerThread(): Thread = {
    if (compileRunner.isAlive) {
      try {
        this.askShutdown()
        while (compileRunner.isAlive) Thread.sleep(0)
      } catch {
        case NonFatal(e) =>
          logger.log(
            Level.INFO,
            "unexpected error shutting down presentation compiler thread",
            e
          )
      }
    }
    this.scheduler = new WorkScheduler
    compileRunner = new MetalsGlobalThread(this, "Metals")
    compileRunner.setDaemon(true)
    compileRunner.start()
    compileRunner
  }
}
