package scala.meta.internal.metals.debug.server

import scala.concurrent.ExecutionContext

import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener

class BuildToolDebugAdapter(
    command: List[String],
    workspace: AbsolutePath,
    env: Map[String, String],
    project: DebugeeProject,
    userJavaHome: Option[String],
)(implicit ec: ExecutionContext)
    extends MetalsDebuggee(project, userJavaHome) {

  def name: String =
    s"${getClass.getSimpleName}(${project.name}, ${command.headOption.getOrElse("")})"

  def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val logger = new Logger(listener)
    scribe.debug(
      s"Starting build tool debug session: ${command.mkString(" ")} (cwd=$workspace)"
    )
    val process = SystemProcess.run(
      cmd = command,
      cwd = workspace,
      redirectErrorOutput = false,
      env = env,
      processOut = Some(logger.logOutput),
      processErr = Some(logger.logError),
    )

    new CancelableFuture[Unit] {
      def future = process.complete.map { code =>
        if (code != 0)
          throw new Exception(
            s"build tool process exited with code $code: ${command.mkString(" ")}"
          )
      }
      def cancel(): Unit = process.cancel
    }
  }
}
