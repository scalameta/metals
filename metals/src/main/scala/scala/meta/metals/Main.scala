package scala.meta.metals

import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import scala.meta.jsonrpc.BaseProtocolMessage
import scala.util.Properties
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.io.PathIO
import scala.meta.lsp.LanguageClient
import scala.meta.lsp.LanguageServer

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    val cwd = PathIO.workingDirectory
    val configDir = cwd.resolve(".metals").toNIO
    val logFile = configDir.resolve("metals.log").toFile
    Files.createDirectories(configDir)
    val out = new PrintStream(new FileOutputStream(logFile))
    val err = new PrintStream(new FileOutputStream(logFile))
    val stdin = System.in
    val stdout = System.out
    val stderr = System.err
    try {
      // route System.out somewhere else. Any output not from the server (e.g. logging)
      // messes up with the client, since stdout is used for the language server protocol
      System.setOut(out)
      System.setErr(err)
      logger.info(s"Starting server in $cwd")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      val s = MSchedulers()
      val client = new LanguageClient(stdout, logger)
      val services = new MetalsServices(cwd, client, s)
      val messages = BaseProtocolMessage
        .fromInputStream(stdin)
        .executeOn(s.lsp)
      val languageServer = new LanguageServer(
        messages,
        client,
        services.services,
        s.global,
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
