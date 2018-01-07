package scala.meta.languageserver.sbtserver

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import scala.meta.languageserver.ActiveJson
import scala.meta.languageserver.MissingActiveJson
import scala.meta.languageserver.SbtInitializeParams
import scala.util.Try
import com.typesafe.scalalogging.LazyLogging
import io.circe.jawn.parseByteBuffer
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import org.langmeta.io.AbsolutePath
import org.langmeta.jsonrpc.BaseProtocolMessage
import org.langmeta.jsonrpc.JsonRpcClient
import org.langmeta.jsonrpc.Services
import org.langmeta.lsp.LanguageClient
import org.langmeta.lsp.LanguageServer
import org.langmeta.lsp.TextDocument
import org.langmeta.lsp.Window
import sbt.internal.NGUnixDomainSocket

class SbtServer(cwd: AbsolutePath, lspClient: JsonRpcClient)(
    implicit scheduler: Scheduler
) extends LazyLogging {
  val forwardingServices: Services = Services.empty
    .notification(Window.logMessage) { msg =>
      lspClient.notify(Window.logMessage, msg)
    }
    .notification(TextDocument.publishDiagnostics) { msg =>
      lspClient.notify(TextDocument.publishDiagnostics, msg)
    }

  private def fail(message: String) = Task.now(Left(message))
  def connect: Task[Either[String, (JsonRpcClient, Cancelable)]] = {
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
        fail(msg + ". Check .metaserver/metaserver.log")
      case Right(socket) =>
        implicit val client: LanguageClient =
          new LanguageClient(socket.getOutputStream, logger)
        val messages =
          BaseProtocolMessage.fromInputStream(socket.getInputStream)
        val server =
          new LanguageServer(messages, client, forwardingServices, scheduler)
        val stopServer = server.startTask.runAsync
        val initialize = client.request(Sbt.initialize, SbtInitializeParams())
        initialize.map { _ =>
          Right(client -> stopServer)
        }
    }
  }
}

object SbtServer extends LazyLogging {
  def activeJson(cwd: AbsolutePath): AbsolutePath =
    cwd.resolve("project").resolve("target").resolve("active.json")

  def openSocketConnection(
      cwd: AbsolutePath
  ): Either[Throwable, NGUnixDomainSocket] = {
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
          Try(new NGUnixDomainSocket(uri.getPath)).toEither
        case invalid =>
          Left(new IllegalArgumentException(s"Unsupported scheme $invalid"))
      }
    } yield socket
  }
}
