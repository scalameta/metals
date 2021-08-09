package scala.meta.internal.pc

import scala.meta.internal.pc.CompilerWrapper
import scala.meta.internal.pc.ReporterAccess

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.reporting.StoreReporter

class Scala3CompilerWrapper(driver: MetalsDriver)
    extends CompilerWrapper[StoreReporter, MetalsDriver] {

  override def compiler(): MetalsDriver = driver

  override def resetReporter(): Unit = ()

  override def reporterAccess: ReporterAccess[StoreReporter] =
    new ReporterAccess[StoreReporter] {
      def reporter = new StoreReporter(null)
    }

  override def askShutdown(): Unit = {}

  override def isAlive(): Boolean = false

  override def stop(): Unit = {}

  override def presentationCompilerThread: Option[Thread] = None

}
