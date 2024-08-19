package scala.meta.internal.metals.debug

import java.io.PrintWriter
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.function.Consumer

import scala.meta.internal.metals.debug.EndpointLogger.Direction
import scala.meta.internal.metals.debug.EndpointLogger.Received
import scala.meta.internal.metals.debug.EndpointLogger.Sent
import scala.meta.internal.metals.debug.EndpointLogger.time

import com.google.gson.GsonBuilder
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.debug.json.DebugMessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage

final class EndpointLogger(endpoint: RemoteEndpoint, logger: PrintWriter)
    extends RemoteEndpoint {
  private val writer: MessageJsonHandler = EndpointLogger.serializer

  override def consume(message: Message): Unit = {
    try log(Sent, message)
    finally endpoint.consume(message)
  }

  override def listen(messageConsumer: MessageConsumer): Unit = {
    endpoint.listen { message =>
      try log(Received, message)
      finally messageConsumer.consume(message)
    }
  }

  override def cancel(): Unit = {
    endpoint.cancel()
    logger.close()
  }

  private def log(direction: Direction, message: Message): Unit =
    synchronized {
      logger.println(s"[Trace][$time] $direction ${typeOf(message)}:")
      writer.serialize(message, logger)
      logger.println()
      logger.flush()
    }

  private def typeOf(message: Message): String = {
    message match {
      case _: RequestMessage => "request"
      case _: ResponseMessage => "response"
      case _: NotificationMessage => "notification"
    }
  }
}

object EndpointLogger {
  sealed trait Direction
  case object Received extends Direction
  case object Sent extends Direction

  private def serializer: MessageJsonHandler = {
    val configure: Consumer[GsonBuilder] = { gson => gson.setPrettyPrinting() }
    new DebugMessageJsonHandler(Collections.emptyMap(), configure)
  }

  private val clock = Clock.systemDefaultZone()
  private val timeFormat =
    DateTimeFormatter.ofPattern("KK:mm:ss a").withZone(clock.getZone)

  def time: String = timeFormat.format(clock.instant())
}
