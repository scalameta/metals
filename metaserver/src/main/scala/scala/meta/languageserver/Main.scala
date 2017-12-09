package scala.meta.languageserver

import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.util.Properties
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.langmeta.io.AbsolutePath

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    // FIXME(gabro): this is vscode specific (at least the name)
    val workspace = System.getProperty("vscode.workspace")
    val logPath = s"$workspace/target/metaserver.log"
    val out = new PrintStream(new FileOutputStream(logPath))
    val err = new PrintStream(new FileOutputStream(logPath))
    val cwd = AbsolutePath(workspace)
    val config = ServerConfig(cwd)
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
      logger.info(s"Starting server in $workspace")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      val server = new ScalametaLanguageServer(config, stdin, stdout, out)
      server.start()
    } finally {
      System.setOut(stdout)
      System.setErr(stderr)
    }

    System.exit(0)
  }
}
