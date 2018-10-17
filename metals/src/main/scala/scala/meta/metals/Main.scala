package scala.meta.metals

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services._
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.GlobalLogging
import scala.meta.internal.metals.MetalsLanguageServer
import scala.util.control.NonFatal

object Main {
  def main(args: Array[String]): Unit = {
    val systemIn = System.in
    val systemOut = System.out
    val server = new MetalsLanguageServer
    val tracePrinter = GlobalLogging.setup("LSP")
    try {
      val launcher = new Launcher.Builder[LanguageClient]()
        .traceMessages(tracePrinter)
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
    }
  }

}
