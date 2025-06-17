package scala.meta.internal.metals.debug.server

import scala.concurrent.Future

import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener
class AttachRemoteDebugAdapter(
    project: DebugeeProject,
    userJavaHome: Option[String],
) extends MetalsDebuggee(project, userJavaHome) {
  def name: String =
    s"${getClass.getSimpleName}(${project.name})"
  def run(listener: DebuggeeListener): CancelableFuture[Unit] =
    new CancelableFuture[Unit] {
      override def future: Future[Unit] = Future.successful(())
      override def cancel(): Unit = ()
    }
}
