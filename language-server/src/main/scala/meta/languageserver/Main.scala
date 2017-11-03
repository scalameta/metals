package scala.meta.languageserver

import scala.util.Properties
import com.typesafe.scalalogging.LazyLogging
import java.io.{PrintStream, OutputStream, FileOutputStream}

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    // FIXME(gabro): this is vscode specific (at least the name)
    val cwd = System.getProperty("vscode.workspace")
    val server = new ScalametaLanguageServer(System.in, System.out)

    // route System.out somewhere else. Any output not from the server (e.g. logging)
    // messes up with the client, since stdout is used for the language server protocol
    val origOut = System.out
    try {
      System.setOut(new PrintStream(new FileOutputStream(s"$cwd/pc.stdout.log")))
      System.setErr(new PrintStream(new FileOutputStream(s"$cwd/pc.stdout.log")))
      logger.info(s"Starting server in $cwd")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      server.start()
    } finally {
      System.setOut(origOut)
    }

    System.exit(0)
  }
}

