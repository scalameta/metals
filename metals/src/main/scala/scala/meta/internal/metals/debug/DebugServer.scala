package scala.meta.internal.metals.debug

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.{util => ju}
import java.util.concurrent.TimeUnit
import ch.epfl.scala.{bsp4j => b}
import com.google.common.net.InetAddresses
import com.google.gson.JsonElement
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.util.Failure
import scala.util.Try

final class DebugServer(val session: DebugSession, connect: () => Future[Proxy])(
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
      parameters: b.DebugSessionParams,
      buildServer: => Option[BuildServerConnection]
  )(implicit ec: ExecutionContext): Try[DebugServer] = {
    parseSessionName(parameters).map { sessionName =>
      val proxyServer = new ServerSocket(0)
      val host = InetAddresses.toUriString(proxyServer.getInetAddress)
      val port = proxyServer.getLocalPort
      val uri = URI.create(s"tcp://$host:$port")

      val awaitClient = () => Future(proxyServer.accept())

      val connectToServer = () => {
        buildServer
          .map(_.startDebugSession(parameters).asScala)
          .getOrElse(BuildServerUnavailableError)
          .map { uri =>
            val socket = new Socket()

            val address = new InetSocketAddress(uri.getHost, uri.getPort)
            val timeout = TimeUnit.SECONDS.toMillis(10).toInt
            socket.connect(address, timeout)

            socket
          }
      }

      val proxyFactory = () => Proxy.open(awaitClient, connectToServer)
      val session = DebugSession(sessionName, uri.toString)
      val server = new DebugServer(session, proxyFactory)
      server.listen.andThen { case _ => proxyServer.close() }

      server
    }
  }

  private def parseSessionName(
      parameters: b.DebugSessionParams
  ): Try[String] = {
    import scala.meta.internal.metals.JsonParser._
    parameters.getData match {
      case json: JsonElement =>
        parameters.getDataKind match {
          case "scala-main-class" =>
            json.as[b.ScalaMainClass].map(_.getClassName)
          case "scala-test-suites" =>
            json.as[ju.List[String]].map(_.asScala.sorted.mkString(";"))
        }
      case data =>
        val dataType = data.getClass.getSimpleName
        Failure(new IllegalStateException(s"Data is $dataType. Expecting json"))
    }
  }

  private val BuildServerUnavailableError =
    Future.failed(new IllegalStateException("Build server unavailable"))
}
