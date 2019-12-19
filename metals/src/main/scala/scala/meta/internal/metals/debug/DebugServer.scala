package scala.meta.internal.metals.debug

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.{util => ju}
import java.util.concurrent.TimeUnit
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}
import com.google.common.net.InetAddresses
import com.google.gson.JsonElement
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.DefinitionProvider
import scala.util.Failure
import scala.util.Try

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

object DebugServer {
  import scala.meta.internal.metals.MetalsEnrichments._

  def start(
      parameters: b.DebugSessionParams,
      definitionProvider: DefinitionProvider,
      buildTargets: BuildTargets,
      buildServer: => Option[BuildServerConnection]
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    Future.fromTry(parseSessionName(parameters)).flatMap { sessionName =>
      val proxyServer = new ServerSocket(0)
      val host = InetAddresses.toUriString(proxyServer.getInetAddress)
      val port = proxyServer.getLocalPort
      val uri = URI.create(s"tcp://$host:$port")
      val connectedToServer = Promise[Unit]()

      val awaitClient =
        () => Future(proxyServer.accept()).withTimeout(10, TimeUnit.SECONDS)

      // long timeout, since server might take a while to compile the project
      val connectToServer = () => {
        buildServer
          .map(_.startDebugSession(parameters).asScala)
          .getOrElse(BuildServerUnavailableError)
          .withTimeout(60, TimeUnit.SECONDS)
          .map { uri =>
            val socket = connect(uri)
            connectedToServer.trySuccess(())
            socket
          }
      }

      val proxyFactory = { () =>
        val targets = parameters.getTargets.asScala
          .map(_.getUri)
          .map(new BuildTargetIdentifier(_))
        val sourcePathProvider = new SourcePathProvider(
          definitionProvider,
          buildTargets,
          targets.toList
        )
        DebugProxy
          .open(sessionName, sourcePathProvider, awaitClient, connectToServer)
      }
      val server = new DebugServer(sessionName, uri, proxyFactory)

      server.listen.andThen { case _ => proxyServer.close() }

      connectedToServer.future.map(_ => server)
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

  private def connect(uri: URI): Socket = {
    val socket = new Socket()

    val address = new InetSocketAddress(uri.getHost, uri.getPort)
    val timeout = TimeUnit.SECONDS.toMillis(10).toInt
    socket.connect(address, timeout)

    socket
  }

  private val BuildServerUnavailableError =
    Future.failed(new IllegalStateException("Build server unavailable"))
}
