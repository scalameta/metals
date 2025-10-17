package scala.meta.internal.pc

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContextExecutor
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.ShutdownReq
import scala.util.control.NonFatal

import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.VirtualFileParams

class ScalaCompilerWrapper(global: MetalsGlobal)
    extends CompilerWrapper[MetalsReporter, MetalsGlobal] {

  override def compiler(params: VirtualFileParams): MetalsGlobal = {
    if (params.outlineFiles().isPresent()) {
      global.runOutline(params.outlineFiles().get())
    }
    global
  }
  override def compiler(): MetalsGlobal = global

  override def resetReporter(): Unit = global.reporter.reset()

  override def reporterAccess: ReporterAccess[MetalsReporter] =
    new ReporterAccess[MetalsReporter] {
      def reporter = global.reporter.asInstanceOf[MetalsReporter]
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
    logger: org.slf4j.Logger,
    config: PresentationCompilerConfig,
    sh: Option[ScheduledExecutorService],
    newCompiler: () => ScalaCompilerWrapper,
    id: String = ""
)(implicit ec: ExecutionContextExecutor)
    extends CompilerAccess[MetalsReporter, MetalsGlobal](
      logger,
      config,
      sh,
      newCompiler,
      shouldResetJobQueue = false,
      id
    ) {

  def newReporter = new MetalsReporter(new Settings())

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
          Some(s"$t")
        }
      case t => throw t
    }
  }

  protected def ignoreException(t: Throwable): Boolean =
    t match {
      case ShutdownReq => true
    }
}
