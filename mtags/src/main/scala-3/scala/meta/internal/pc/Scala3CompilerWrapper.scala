package scala.meta.internal.pc

import scala.meta.internal.pc.CompilerWrapper
import scala.meta.internal.pc.ReporterAccess
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.core.Contexts.Context

class Scala3CompilerWrapper(driver: InteractiveDriver)
    extends CompilerWrapper[StoreReporter, InteractiveDriver] {

  override def compiler(): InteractiveDriver = driver

  override def resetReporter(): Unit = {
    given ctx as Context = driver.currentCtx
    ctx.reporter.removeBufferedMessages
  }

  override def reporterAccess: ReporterAccess[StoreReporter] =
    new ReporterAccess[StoreReporter] {
      def reporter = driver.currentCtx.reporter.asInstanceOf[StoreReporter]
    }

  override def askShutdown(): Unit = {}

  override def isAlive(): Boolean = false

  override def stop(): Unit = {}

  override def presentationCompilerThread: Option[Thread] = None

}
