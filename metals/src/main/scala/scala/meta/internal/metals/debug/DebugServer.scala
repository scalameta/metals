package scala.meta.internal.metals.debug

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import ch.epfl.scala.bsp4j.DebugSessionParams
import com.google.common.net.InetAddresses
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.BuildServerConnection
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
  import scala.meta.internal.metals.MetalsEnrichments._

  def start(
      parameters: DebugSessionParams,
      buildServer: => Option[BuildServerConnection]
  )(implicit ec: ExecutionContext): (URI, DebugServer) = {
    val proxyServer = new ServerSocket(0)
    val host = InetAddresses.toUriString(proxyServer.getInetAddress)
    val port = proxyServer.getLocalPort
    val uri = URI.create(s"tcp://$host:$port")

    val awaitClient = () => Future(proxyServer.accept())

    val connectToServer = () => {
      buildServer
        .map(_.startDebugSession(parameters).asScala)
        .getOrElse(Future.failed(new IllegalStateException("No build server")))
        .map { uri =>
          val socket = new Socket()

          val address = new InetSocketAddress(uri.getHost, uri.getPort)
          val timeout = TimeUnit.SECONDS.toMillis(10).toInt
          socket.connect(address, timeout)

          socket
        }
    }

    val proxyFactory = () => Proxy.open(awaitClient, connectToServer)
    val server = new DebugServer(proxyFactory)
    server.listen.andThen { case _ => proxyServer.close() }

    (uri, server)
  }
}
