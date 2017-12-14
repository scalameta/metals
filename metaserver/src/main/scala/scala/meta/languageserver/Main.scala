package scala.meta.languageserver

import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Executors
import scala.util.Properties
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.langmeta.internal.io.PathIO

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    val cwd = PathIO.workingDirectory
    val config = ServerConfig(cwd)
    Files.createDirectories(config.configDir.toNIO)
    val out = new PrintStream(new FileOutputStream(config.logFile))
    val err = new PrintStream(new FileOutputStream(config.logFile))
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
      val server = new ScalametaLanguageServer(config, stdin, stdout, out)
      LSPLogger.connection = Some(server.connection)
      server.start()
    } finally {
      System.setOut(stdout)
      System.setErr(stderr)
    }

    System.exit(0)
  }
}
