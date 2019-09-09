package scala.meta.internal.metals.debug

import java.net.Socket

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.Cancelable

final class DebugServer(connect: () => Future[Proxy])(
    implicit ec: ExecutionContext
) extends Cancelable {
  @volatile private var isCancelled = false
  @volatile private var proxy: Proxy = _

  lazy val listen: Future[Unit] = {
    def loop: Future[Unit] = {
      connect().flatMap { proxy =>
        this.proxy = proxy

        if (isCancelled) Future(proxy.cancel())
        else {
          proxy.listen.flatMap {
            case Proxy.Terminated => Future.unit
            case Proxy.Restarted => loop
          }
        }
      }
    }

    loop
  }

  override def cancel(): Unit = {
    isCancelled = true
    if (proxy != null) proxy.cancel()
  }
}

object DebugServer {
  def create(
      awaitClient: () => Future[Socket],
      connectToServer: () => Future[Socket]
  )(implicit ec: ExecutionContext): DebugServer = {
    val proxyFactory = () => Proxy.open(awaitClient, connectToServer)
    new DebugServer(proxyFactory)
  }
}
