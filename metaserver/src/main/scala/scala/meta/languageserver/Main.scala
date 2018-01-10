package scala.meta.languageserver

import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Executors
import org.langmeta.jsonrpc.BaseProtocolMessage
import scala.util.Properties
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.langmeta.internal.io.PathIO
import org.langmeta.lsp.LanguageClient
import org.langmeta.lsp.LanguageServer

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    val cwd = PathIO.workingDirectory
    val configDir = cwd.resolve(".metaserver").toNIO
    val logFile = configDir.resolve("metaserver.log").toFile
    Files.createDirectories(configDir)
    val out = new PrintStream(new FileOutputStream(logFile))
    val err = new PrintStream(new FileOutputStream(logFile))
    val stdin = System.in
    val stdout = System.out
    val stderr = System.err
    implicit val s: SchedulerService =
      Scheduler(Executors.newFixedThreadPool(4))
    try {
      // route System.out somewhere else. Any output not from the server (e.g. logging)
      // messes up with the client, since stdout is used for the language server protocol
      System.setOut(out)
      System.setErr(err)
      logger.info(s"Starting server in $cwd")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      val client = new LanguageClient(stdout, logger)
      val services = new ScalametaServices(cwd, client, s)
      val languageServer = new LanguageServer(
        BaseProtocolMessage.fromInputStream(stdin),
        client,
        services.services,
        s,
        logger
      )
      languageServer.listen()
    } catch {
      case NonFatal(e) =>
        logger.error("Uncaught top-level exception", e)
    } finally {
      System.setOut(stdout)
      System.setErr(stderr)
    }

    System.exit(0)
  }
}
