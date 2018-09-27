package scala.meta.metals

import java.io.PrintStream
import java.nio.file.Files
import scala.meta.jsonrpc.BaseProtocolMessage
import scala.util.Properties
import scala.util.control.NonFatal
import org.langmeta.internal.io.PathIO
import scala.meta.jsonrpc.LanguageClient
import scala.meta.jsonrpc.LanguageServer

object Main extends MetalsLogger {
  def main(args: Array[String]): Unit = {
    val cwd = PathIO.workingDirectory
    val configDir = cwd.resolve(".metals").toNIO
    val logPath = configDir.resolve("metals.log")
    Files.createDirectories(configDir)
    val out = new PrintStream(Files.newOutputStream(logPath))
    val err = new PrintStream(Files.newOutputStream(logPath))
    val stdin = System.in
    val stdout = System.out
    val stderr = System.err
    try {
      // route System.out somewhere else. Any output not from the server (e.g. logging)
      // messes up with the client, since stdout is used for the language server protocol
      System.setOut(out)
      System.setErr(err)
      val filewriter = scribe.writer.FileWriter().path(_ => logPath)
      MetalsLogger.setup(logPath)
      val scribeLogger =
        scribe
          .Logger("metals")
          .orphan()
          .withHandler(writer = filewriter)
          .replace(Some("root"))

      logger.info(s"Starting server in $cwd")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      val s = MSchedulers()
      val client = new LanguageClient(stdout, MetalsLogger.logger)
      val services = new MetalsServices(cwd, client, s)
      val messages = BaseProtocolMessage
        .fromInputStream(stdin, MetalsLogger.logger)
        .executeOn(s.lsp)
      val languageServer = new LanguageServer(
        messages,
        client,
        services.services,
        s.global,
        MetalsLogger.logger
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
