package scala.meta.internal.pc

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContextExecutor
import scala.tools.nsc.interactive.ShutdownReq
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal

import scala.meta.pc.PresentationCompilerConfig

class ScalaCompilerWrapper(global: MetalsGlobal)
    extends CompilerWrapper[StoreReporter, MetalsGlobal] {

  override def compiler(): MetalsGlobal = global

  override def resetReporter(): Unit = global.reporter.reset()

  override def reporterAccess: ReporterAccess[StoreReporter] =
    new ReporterAccess[StoreReporter] {
      def reporter = global.reporter.asInstanceOf[StoreReporter]
    }

  override def askShutdown(): Unit = {
    global.askShutdown()
  }

  override def isAlive(): Boolean = {
    global.presentationCompilerThread.isAlive()
  }

  override def stop(): Unit = {
    global.presentationCompilerThread.stop()
  }

  override def presentationCompilerThread: Option[Thread] = {
    Some(global.presentationCompilerThread)
  }

}

class ScalaCompilerAccess(
    config: PresentationCompilerConfig,
    sh: Option[ScheduledExecutorService],
    newCompiler: () => ScalaCompilerWrapper
)(implicit ec: ExecutionContextExecutor)
    extends CompilerAccess[StoreReporter, MetalsGlobal](
      config,
      sh,
      newCompiler
    ) {

  def newReporter() = new StoreReporter

  protected def handleSharedCompilerException(
      t: Throwable
  ): Option[String] = {
    t match {
      case ShutdownReq =>
        Some("an error in the Scala compiler")
      case NonFatal(e) =>
        val isParadiseRelated = e.getStackTrace
          .exists(_.getClassName.startsWith("org.scalamacros"))
        val isCompilerRelated = e.getStackTrace.headOption.exists { e =>
          e.getClassName.startsWith("scala.tools") ||
          e.getClassName.startsWith("scala.reflect")
        }
        if (isParadiseRelated) {
          // NOTE(olafur) Metals disables macroparadise by default but other library
          // clients of mtags may enable it.
          // Testing shows that the scalamacro paradise plugin tends to crash
          // easily in long-running sessions. We retry with a fresh compiler
          // to see if that fixes the issue. This is a hacky solution that is
          // slow because creating new compiler instances is expensive. A better
          // long-term solution is to fix the paradise plugin implementation
          // to be  more resilient in long-running sessions.
          Some("the org.scalamacros:paradise compiler plugin")
        } else if (isCompilerRelated) {
          Some("an error in the Scala compiler")
        } else {
          None
        }
      case t => throw t
    }
  }

  protected def ignoreException(t: Throwable): Boolean =
    t match {
      case ShutdownReq => true
    }
}
