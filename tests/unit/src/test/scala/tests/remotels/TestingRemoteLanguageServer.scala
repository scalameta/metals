package tests.remotels

import io.undertow.Undertow
import scala.meta.internal.metals.MetalsHttpServer
import io.undertow.Handlers.path
import scala.meta.internal.io.InputStreamIO
import java.nio.charset.StandardCharsets
import scala.meta.internal.metals.JsonParser._
import scala.util.control.NonFatal
import scala.meta.internal.remotels.RemoteLocationResult
import org.eclipse.{lsp4j => l}
import org.eclipse.lsp4j.TextDocumentPositionParams
import java.{util => ju}

class TestingRemoteLanguageServer(server: Undertow) {
  def address: String = {
    MetalsHttpServer.address(server) + "/message"
  }
  def start(): Unit = {
    server.start()
  }
  def stop(): Unit = {
    server.stop()
  }
}
object TestingRemoteLanguageServer {
  def apply(): TestingRemoteLanguageServer = {
    val host = "localhost"
    val port = MetalsHttpServer.freePort(host, 9876)
    val server = Undertow
      .builder()
      .addHttpListener(port, host)
      .setHandler(
        path().addExactPath(
          "/message",
          MetalsHttpServer.textHandler(
            "application/json",
            e => {
              try {
                val request = new String(
                  InputStreamIO.readBytes(e.getInputStream()),
                  StandardCharsets.UTF_8
                ).parseJson.toJsonObject
                val params =
                  request.get("params").as[TextDocumentPositionParams].get
                val response = new RemoteLocationResult(
                  ju.Collections.singletonList(
                    new l.Location(
                      params.getTextDocument().getUri(),
                      new l.Range(params.getPosition(), params.getPosition())
                    )
                  )
                )
                response.toJson.toString()
              } catch {
                case NonFatal(e) =>
                  scribe.error(e)
                  ""
              }
            }
          )
        )
      )
      .build()
    new TestingRemoteLanguageServer(server)
  }
}
