package scala.meta.metals

import io.github.soc.directories.ProjectDirectories
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services._
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsLogger
import scala.util.control.NonFatal

object Main {
  def main(args: Array[String]): Unit = {
    val systemIn = System.in
    val systemOut = System.out
    val server = new MetalsLanguageServer
    val tracePrinter = setupGlobalLogging()
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

  /** Returns a printer to trace JSON messages if the user opts into it. */
  private def setupGlobalLogging(): PrintWriter = {
    val projectDirectories =
      ProjectDirectories.from("org", "scalameta", "metals")
    val logFile = Paths.get(projectDirectories.dataDir).resolve("global.log")
    MetalsLogger.redirectSystemOut(logFile)
    val tracemessages = logFile.resolveSibling("tracemessages.json")
    val isTraceMessages = Files.exists(tracemessages)
    if (isTraceMessages) {
      val fos = Files.newOutputStream(
        tracemessages,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING // don't append infinitely to existing file
      )
      new PrintWriter(fos)
    } else {
      scribe.info(
        s"Tracing is disabled, to log every JSON message create '$tracemessages'"
      )
      null
    }
  }
}
