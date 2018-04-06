package scala.meta.metals.sbtserver

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import scala.meta.metals.ActiveJson
import scala.meta.metals.MissingActiveJson
import scala.meta.metals.SbtInitializeParams
import scala.meta.metals.Configuration
import scala.util.Try
import com.typesafe.scalalogging.LazyLogging
import io.circe.jawn.parseByteBuffer
import monix.eval.Task
import monix.execution.CancelableFuture
import monix.execution.Scheduler
import org.langmeta.io.AbsolutePath
import org.langmeta.jsonrpc.BaseProtocolMessage
import org.langmeta.jsonrpc.JsonRpcClient
import org.langmeta.jsonrpc.Services
import org.langmeta.lsp.LanguageClient
import org.langmeta.lsp.LanguageServer
import org.langmeta.lsp.TextDocument
import org.langmeta.lsp.Window
import org.scalasbt.ipcsocket.UnixDomainSocket

/**
 * A wrapper around a connection to an sbt server.
 *
 * @param client client that can send requests and notifications
 *               to the sbt server.
 * @param runningServer The running client listening for requests from the server.
 *               Use runningServer.cancel() to stop disconnect to this server.
 *               Use runningServer.onComplete to attach callbacks on
 *               disconnect.
 *
 */
case class SbtServer(
    client: JsonRpcClient,
    runningServer: CancelableFuture[Unit]
)

object SbtServer extends LazyLogging {
  private def fail(message: String) = Task.now(Left(message))

  /**
   * Establish connection with sbt server.
   *
   * Requires sbt 1.1.0 and above.
   *
   * @see http://www.scala-sbt.org/1.x-beta/docs/sbt-server.html
   *
   * @param cwd The workspace directory, baseDirectory.in(ThisBuild).
   * @param services the handler for requests/notifications/responses from
   *                 the sbt server.
   * @param scheduler the scheduler on which to run the services handling
   *                  sbt responses and notifications.
   * @return A client to communicate with sbt server in case of success or a
   *         user-friendly error message if something went wrong in case of
   *         failure.
   */
  def connect(cwd: AbsolutePath, services: Services)(
      implicit scheduler: Scheduler
  ): Task[Either[String, SbtServer]] = {
    Task(SbtServer.openSocketConnection(cwd)).flatMap {
      case Left(err: MissingActiveJson) =>
        fail(err.getMessage)
      case Left(_: IOException) =>
        fail(
          s"Unable to establish connection with sbt server. " +
            s"Do you have an active sbt 1.1.0 session?"
        )
      case Left(err) =>
        val msg = s"Unexpected error opening connection to sbt server"
        logger.error(msg, err)
        fail(msg + ". Check .metals/metals.log")
      case Right(socket) =>
        val client: LanguageClient =
          new LanguageClient(socket.getOutputStream, logger)
        val messages =
          BaseProtocolMessage.fromInputStream(socket.getInputStream)
        val server =
          new LanguageServer(messages, client, services, scheduler, logger)
        val runningServer =
          server.startTask.doOnCancel(Task.eval(socket.close())).runAsync
        val initialize = client.request(Sbt.initialize, SbtInitializeParams())
        initialize.map { _ =>
          Right(SbtServer(client, runningServer))
        }
    }
  }

  /**
   * Handler that forwards logMessage and publishNotifications to the sbt server.
   *
   * @param editorClient the LSP editor client to forward the notifications
   *                     from the sbt server.
   */
  def forwardingServices(
      editorClient: JsonRpcClient,
      config: () => Configuration
  ): Services =
    Services.empty
      .notification(Window.logMessage) { msg =>
        editorClient.notify(Window.logMessage, msg)
      }
      .notification(TextDocument.publishDiagnostics) { msg =>
        if (config().sbt.diagnostics.enabled) {
          editorClient.notify(TextDocument.publishDiagnostics, msg)
        }
      }

  /**
   * Returns path to project/target/active.json from the base directory of an sbt build.
   */
  def activeJson(cwd: AbsolutePath): AbsolutePath =
    cwd.resolve("project").resolve("target").resolve("active.json")

  /**
   * Establishes a unix domain socket connection with sbt server.
   */
  def openSocketConnection(
      cwd: AbsolutePath
  ): Either[Throwable, UnixDomainSocket] = {
    val active = activeJson(cwd)
    for {
      bytes <- {
        if (Files.exists(active.toNIO)) Right(Files.readAllBytes(active.toNIO))
        else Left(MissingActiveJson(active))
      }
      parsed <- parseByteBuffer(ByteBuffer.wrap(bytes))
      activeJson <- parsed.as[ActiveJson]
      uri <- Try(URI.create(activeJson.uri)).toEither
      socket <- uri.getScheme match {
        case "local" =>
          logger.info(s"Connecting to sbt server socket ${uri.getPath}")
          Try(new UnixDomainSocket(uri.getPath)).toEither
        case invalid =>
          Left(new IllegalArgumentException(s"Unsupported scheme $invalid"))
      }
    } yield socket
  }
}
