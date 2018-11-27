package scala.meta.metals

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import org.eclipse.lsp4j.jsonrpc.Launcher
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.GlobalTrace
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ConfiguredLanguageClient
import scala.util.control.NonFatal

object Main {
  def main(args: Array[String]): Unit = {
    val systemIn = System.in
    val systemOut = System.out
    val tracePrinter = GlobalTrace.setup("LSP")
    val exec = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutorService(exec)
    val config = MetalsServerConfig.default
    val server = new MetalsLanguageServer(
      ec,
      redirectSystemOut = true,
      charset = StandardCharsets.UTF_8,
      config = config
    )
    try {
      scribe.info(s"Starting Metals server with configuration: $config")
      val launcher = new Launcher.Builder[MetalsLanguageClient]()
        .traceMessages(tracePrinter)
        .setExecutorService(exec)
        .setInput(systemIn)
        .setOutput(systemOut)
        .setRemoteInterface(classOf[MetalsLanguageClient])
        .setLocalService(server)
        .create()
      val underlyingClient = launcher.getRemoteProxy
      val client = new ConfiguredLanguageClient(underlyingClient, config)(ec)
      server.connectToLanguageClient(client)
      launcher.startListening().get()
    } catch {
      case NonFatal(e) =>
        e.printStackTrace(systemOut)
        sys.exit(1)
    } finally {
      exec.shutdown()
    }
  }

}
