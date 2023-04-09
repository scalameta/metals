package scala.meta.internal.pc

import dotty.tools.dotc.reporting.StoreReporter

class Scala3CompilerWrapper(driver: MetalsDriver)
    extends CompilerWrapper[StoreReporter, MetalsDriver]:

  override def compiler(): MetalsDriver = driver

  override def resetReporter(): Unit =
    val ctx = driver.currentCtx
    ctx.reporter.removeBufferedMessages(using ctx)

  override def reporterAccess: ReporterAccess[StoreReporter] =
    new ReporterAccess[StoreReporter]:
      def reporter = driver.currentCtx.reporter.asInstanceOf[StoreReporter]

  override def askShutdown(): Unit = ()

  override def isAlive(): Boolean = false

  override def stop(): Unit = {}

  override def presentationCompilerThread: Option[Thread] = None
end Scala3CompilerWrapper
