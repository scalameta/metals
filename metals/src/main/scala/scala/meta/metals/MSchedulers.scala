package scala.meta.metals

import java.util.concurrent.Executors
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService

/**
 * Utility to manage monix schedulers.
 *
 * @param global The default scheduler when you are unsure which one to use.
 * @param lsp to communicate with LSP editor client.
 * @param sbt to communicate with sbt server.
 * @param bsp to communicate with bsp server.
 */
case class MSchedulers(
    global: Scheduler,
    lsp: Scheduler,
    sbt: Scheduler,
    bsp: Scheduler
)
object MSchedulers {
  def apply(): MSchedulers = new MSchedulers(main, lsp, sbt, bsp)
  lazy val main: SchedulerService =
    Scheduler(Executors.newFixedThreadPool(4))
  lazy val lsp: SchedulerService =
    Scheduler(Executors.newFixedThreadPool(1))
  lazy val sbt: SchedulerService =
    Scheduler(Executors.newFixedThreadPool(3))
  lazy val bsp: SchedulerService =
    Scheduler(Executors.newFixedThreadPool(3))
}
