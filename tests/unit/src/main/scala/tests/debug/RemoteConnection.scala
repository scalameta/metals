package tests.debug

import java.net.Socket
import java.util.concurrent.ExecutorService

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.debug.DebugLauncher

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.CancelableFuture
import scala.reflect.ClassTag
import scala.reflect.classTag

trait RemoteConnection extends Cancelable {
  protected def connection: CancelableFuture[Unit]

  final def listening: Future[Unit] = connection.future

  override final def cancel(): Unit = {
    connection.cancel()
  }
}

object RemoteConnection {
  def start(launcher: Launcher[_], socket: Socket)(
      implicit executor: ExecutionContext
  ): CancelableFuture[Unit] = {
    val cancelable = launcher.startListening()
    val future = Future(cancelable.get()).andThen { case _ => socket.close() }
    new CancelableFuture(future.ignoreValue, () => cancelable.cancel(true))
  }

  def builder[A: ClassTag](socket: Socket, service: Any)(
      implicit executor: ExecutorService
  ): Launcher.Builder[A] = {
    new DebugLauncher.Builder[A]
      .setRemoteInterface(classTag[A].runtimeClass.asInstanceOf[Class[A]])
      .validateMessages(true)
      .setExecutorService(executor)
      .setInput(socket.getInputStream)
      .setOutput(socket.getOutputStream)
      .setLocalService(service)
  }
}
