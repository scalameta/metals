package scala.meta.metals.bsp

import scala.meta.lsp.LanguageClient
import scala.meta.lsp.LanguageServer
import scala.meta.jsonrpc.BaseProtocolMessage
import scala.meta.jsonrpc.Services
import monix.eval.Task
import monix.execution.Scheduler
import java.nio.file.Files
import java.nio.file.Paths
import com.typesafe.scalalogging.LazyLogging

class BloopBspConnector(services: Services)(implicit scheduler: Scheduler)
    extends BspConnector
    with LazyLogging {
  private val socketPath = "/var/tmp/bsp"

  def openServerConnection(): Task[Either[String, Connection]] = Task.eval {
    Files.deleteIfExists(Paths.get(socketPath))
    val bspProcess =
      new ProcessBuilder("bloop", "bsp", "-s", socketPath).start()
    val client: LanguageClient =
      new LanguageClient(bspProcess.getOutputStream, logger)
    val messages =
      BaseProtocolMessage.fromInputStream(bspProcess.getInputStream, logger)
    val server =
      new LanguageServer(messages, client, services, scheduler, logger)
    val runningServer =
      server.startTask.doOnCancel(Task.eval(bspProcess.destroy())).runAsync
    Right((client, runningServer))
  }
}
