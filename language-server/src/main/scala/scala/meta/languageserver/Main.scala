package scala.meta.languageserver

import java.io.FileOutputStream
import java.io.PrintStream
import scala.util.Properties
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import monix.execution.Scheduler.Implicits.global // TODO(olafur) may want to customize

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    // FIXME(gabro): this is vscode specific (at least the name)
    val workspace = System.getProperty("vscode.workspace")
    val out = new PrintStream(new FileOutputStream(s"$workspace/pc.stdout.log"))
    val err = new PrintStream(new FileOutputStream(s"$workspace/pc.stdout.log"))
    val cwd = AbsolutePath(workspace)
    val server =
      new ScalametaLanguageServer(cwd, System.in, System.out, out)

    // route System.out somewhere else. Any output not from the server (e.g. logging)
    // messes up with the client, since stdout is used for the language server protocol
    val origOut = System.out
    try {
      System.setOut(out)
      System.setErr(err)
      logger.info(s"Starting server in $workspace")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      server.start()
    } finally {
      System.setOut(origOut)
    }

    System.exit(0)
  }
}
