package scala.meta.internal.metals.debug

import java.net.URI

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Cancelable

final class DebugServer(
    val sessionName: String,
    val uri: URI,
    connect: () => Future[DebugProxy]
)(implicit ec: ExecutionContext)
    extends Cancelable {
  @volatile private var isCancelled = false
  @volatile private var proxy: DebugProxy = _

  lazy val listen: Future[Unit] = {
    def loop: Future[Unit] = {
      connect().flatMap { proxy =>
        this.proxy = proxy

        if (isCancelled) Future(proxy.cancel())
        else {
          proxy.listen.flatMap {
            case DebugProxy.Terminated => Future.unit
            case DebugProxy.Restarted => loop
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
