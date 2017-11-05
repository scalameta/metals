package scala.meta.languageserver

import java.io.FileOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Paths
import scala.util.Properties
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    // FIXME(gabro): this is vscode specific (at least the name)
    val workspace = System.getProperty("vscode.workspace")
    val cwd = AbsolutePath(workspace)
    val server = new ScalametaLanguageServer(cwd, System.in, System.out)

    // route System.out somewhere else. Any output not from the server (e.g. logging)
    // messes up with the client, since stdout is used for the language server protocol
    val origOut = System.out
    try {
      System.setOut(
        new PrintStream(new FileOutputStream(s"$workspace/pc.stdout.log")))
      System.setErr(
        new PrintStream(new FileOutputStream(s"$workspace/pc.stdout.log")))
      logger.info(s"Starting server in $workspace")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      server.start()
    } finally {
      System.setOut(origOut)
    }

    System.exit(0)
  }
}
