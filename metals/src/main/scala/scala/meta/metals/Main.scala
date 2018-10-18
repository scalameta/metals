package scala.meta.metals

import java.util.concurrent.Executors
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services._
import scala.concurrent.ExecutionContext
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.GlobalLogging
import scala.meta.internal.metals.MetalsLanguageServer
import scala.util.control.NonFatal

object Main {
  def main(args: Array[String]): Unit = {
    val systemIn = System.in
    val systemOut = System.out
    val tracePrinter = GlobalLogging.setup("LSP")
    val exec = Executors.newCachedThreadPool()
    val server = new MetalsLanguageServer(
      ExecutionContext.fromExecutorService(exec)
    )
    try {
      val launcher = new Launcher.Builder[LanguageClient]()
        .traceMessages(tracePrinter)
        .setExecutorService(exec)
        .setInput(systemIn)
        .setOutput(systemOut)
        .setRemoteInterface(classOf[LanguageClient])
        .setLocalService(server)
        .create()
      scribe.info(
        s"starting server in working directory ${PathIO.workingDirectory}"
      )
      server.connect(launcher.getRemoteProxy)
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
