package scala.meta.internal.metals

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Collections

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.metals.MetalsEnrichments.given

import io.undertow.Handlers.path
import io.undertow.Handlers.websocket
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.BlockingHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core.AbstractReceiveListener
import io.undertow.websockets.core.BufferedTextMessage
import io.undertow.websockets.core.StreamSourceFrameChannel
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.core.WebSockets
import io.undertow.websockets.spi.WebSocketHttpExchange
import org.eclipse.lsp4j.ExecuteCommandParams

/**
 * Http server
 */
final class MetalsHttpServer private (
    server: Undertow,
    openChannels: mutable.Set[WebSocketChannel],
) extends Cancelable {
  override def cancel(): Unit = stop()
  def address: String =
    MetalsHttpServer.address(server)
  def start(): Unit = {
    server.start()
    scribe.info(s"Started Metals http server at $address")
  }
  def stop(): Unit = {
    server.stop()
  }
  def reload(): Unit = {
    sendJson(s"""{"command":"reload","path":"index.html","liveCss":true}""")
  }
  def alert(message: String): Unit = {
    sendJson(s"""{"command":"alert","message":"$message"}""")
  }
  private def sendJson(json: String): Unit = {
    openChannels.foreach(channel => WebSockets.sendTextBlocking(json, channel))
  }
}

object MetalsHttpServer {

  /**
   * Instantiate an undertow file server that speaks the LiveReload protocol.
   *
   * See LiveReload protocol for more details: http://livereload.com/api/protocol/
   *
   * @param host the hostname of the server.
   * @param preferredPort the preferred port of the server. If the port is unavailable,
   *                      then a random free port will be chosen.
   */
  def apply(
      host: String,
      preferredPort: Int,
      render: () => String,
      complete: HttpServerExchange => Unit,
      doctor: () => String,
      tasty: (URI) => Future[Either[String, String]],
      server: WorkspaceLspService,
  )(implicit ec: ExecutionContext): MetalsHttpServer = {
    val port = freePort(host, preferredPort)
    scribe.info(s"Selected port $port")
    val openChannels = mutable.Set.empty[WebSocketChannel]
    val baseHandler =
      path()
        .addExactPath("/livereload.js", staticResource("/livereload.js"))
        .addPrefixPath(
          "/complete",
          new HttpHandler {
            override def handleRequest(exchange: HttpServerExchange): Unit = {
              try complete(exchange)
              catch {
                case NonFatal(e) =>
                  scribe.error(
                    s"http error: ${exchange.getRequestPath} ${exchange.getQueryString}",
                    e,
                  )
              }
              exchange.setStatusCode(StatusCodes.SEE_OTHER)
              exchange.getResponseHeaders.put(Headers.LOCATION, "/")
              exchange.endExchange()
            }
          },
        )
        .addPrefixPath(
          "/execute-command",
          new HttpHandler {
            override def handleRequest(exchange: HttpServerExchange): Unit = {
              val command = for {
                params <- Option(exchange.getQueryParameters.get("command"))
                command <- params.asScala.headOption
              } yield command
              server.executeCommand(
                new ExecuteCommandParams(
                  command.getOrElse("<unknown command>"),
                  Collections.emptyList(),
                )
              )
              exchange.setStatusCode(StatusCodes.SEE_OTHER)
              exchange.getResponseHeaders.put(Headers.LOCATION, "/")
              exchange.endExchange()
            }
          },
        )
        .addPrefixPath(
          "/livereload",
          websocket(new LiveReloadConnectionCallback(openChannels)),
        )
        .addPrefixPath(
          "/tasty",
          tastyEndpointHandler(tasty),
        )
        .addExactPath("/", textHtmlHandler(render))
        .addExactPath("/doctor", textHtmlHandler(doctor))
    val httpServer = Undertow.builder
      .addHttpListener(port, host)
      .setHandler(baseHandler)
      .build()
    new MetalsHttpServer(httpServer, openChannels)
  }

  def address(server: Undertow): String = {
    server.getListenerInfo.asScala.headOption match {
      case Some(listener) =>
        s"${listener.getProtcol}:/" + listener.getAddress.toString
      case None =>
        ""
    }
  }

  def textHtmlHandler(render: () => String): HttpHandler =
    textHandler("text/html", _ => render())
  def textHandler(
      contentType: String,
      render: HttpServerExchange => String,
  ): HttpHandler =
    new BlockingHandler(new HttpHandler {
      override def handleRequest(exchange: HttpServerExchange): Unit = {
        val response = render(exchange)
        exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, contentType)
        exchange.getResponseSender.send(response)
      }
    })

  final def freePort(host: String, port: Int, maxRetries: Int = 20): Int = {
    try {
      val socket = new ServerSocket()
      try {
        socket.bind(new InetSocketAddress(host, port))
        val free = socket.getLocalPort
        free
      } finally {
        socket.close()
      }
    } catch {
      case NonFatal(_: IOException) if maxRetries > 0 =>
        freePort(host, port + 1, maxRetries - 1)
    }
  }

  private def tastyEndpointHandler(
      tasty: (URI) => Future[Either[String, String]]
  )(implicit ec: ExecutionContext) = new HttpHandler {
    override def handleRequest(exchange: HttpServerExchange): Unit = {
      exchange.dispatch { () =>
        val uri: Option[URI] = for {
          params <- Option(exchange.getQueryParameters.get("file"))
          path <- params.asScala.headOption
        } yield new URI(path)

        uri match {
          case Some(uri) =>
            tasty(uri)
              .onComplete {
                case Success(response) =>
                  response match {
                    case Right(value) =>
                      exchange.getResponseSender().send(value)
                    case Left(value) =>
                      exchange
                        .setStatusCode(StatusCodes.BAD_REQUEST)
                        .getResponseSender()
                        .send(value)
                  }
                case Failure(e) =>
                  exchange
                    .setStatusCode(StatusCodes.BAD_REQUEST)
                    .getResponseSender()
                    .send(e.getMessage())
              }
          case None =>
            exchange
              .setStatusCode(StatusCodes.BAD_REQUEST)
              .getResponseSender()
              .send(
                "Missing query parameter file or provided value has invalid format. File should be an absolute URI"
              )
        }
      }
    }
  }

  private def staticResource(path: String): HttpHandler = {
    val is = this.getClass.getResourceAsStream(path)
    if (is == null) throw new NoSuchElementException(path)
    val bytes =
      try InputStreamIO.readBytes(is)
      finally is.close()
    val text = new String(bytes, StandardCharsets.UTF_8)
    new HttpHandler {
      override def handleRequest(exchange: HttpServerExchange): Unit = {
        exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, contentType(path))
        exchange.getResponseSender.send(text)
      }
    }
  }

  private def contentType(path: String): String = {
    if (path.endsWith(".js")) "application/javascript"
    else if (path.endsWith(".css")) "text/css"
    else if (path.endsWith(".html")) "text/html"
    else ""
  }

  private final class LiveReloadConnectionCallback(
      openChannels: mutable.Set[WebSocketChannel]
  ) extends WebSocketConnectionCallback {
    override def onConnect(
        exchange: WebSocketHttpExchange,
        channel: WebSocketChannel,
    ): Unit = {
      channel.getReceiveSetter.set(new AbstractReceiveListener() {
        override def onClose(
            webSocketChannel: WebSocketChannel,
            channel: StreamSourceFrameChannel,
        ): Unit = {
          openChannels.remove(webSocketChannel)
          super.onClose(webSocketChannel, channel)
        }
        override protected def onFullTextMessage(
            channel: WebSocketChannel,
            message: BufferedTextMessage,
        ): Unit = {
          if (message.getData.contains("""command":"hello""")) {
            val hello =
              """{"command":"hello","protocols":["http://livereload.com/protocols/official-7"],"serverName":"mdoc"}"""
            WebSockets.sendTextBlocking(hello, channel)
            openChannels.add(channel)
          }
        }
      })
      channel.resumeReceives()
    }
  }
}
