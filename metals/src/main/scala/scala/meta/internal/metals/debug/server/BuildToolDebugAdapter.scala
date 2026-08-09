package scala.meta.internal.metals.debug.server

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.process.ProcessOutput
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener

class BuildToolDebugAdapter(
    command: Future[List[String]],
    workspace: AbsolutePath,
    env: Map[String, String],
    project: DebugeeProject,
    userJavaHome: Option[String],
)(implicit ec: ExecutionContext)
    extends MetalsDebuggee(project, userJavaHome) {

  def name: String =
    s"${getClass.getSimpleName}(${project.name})"

  def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val logger = new Logger(listener)
    val cancelled = new AtomicBoolean(false)
    val processRef = new AtomicReference[Option[SystemProcess]](None)

    val completeFuture: Future[Unit] = command.flatMap { cmd =>
      scribe.debug(
        s"Starting build tool debug session: ${cmd.mkString(" ")} (cwd=$workspace)"
      )
      val process = SystemProcess.run(
        cmd = cmd,
        cwd = workspace,
        redirectErrorOutput = false,
        env = env,
        processOut = Some(ProcessOutput.Lines(logger.logOutput)),
        processErr = Some(logger.logError),
      )
      processRef.set(Some(process))
      if (cancelled.get()) process.cancel
      process.complete.map { code =>
        if (code != 0)
          throw new Exception(
            s"build tool process exited with code $code: ${cmd.mkString(" ")}"
          )
      }
    }

    new CancelableFuture[Unit] {
      def future = completeFuture
      def cancel(): Unit = {
        cancelled.set(true)
        processRef.get().foreach(_.cancel)
      }
    }
  }
}
