package scala.meta.languageserver

import java.io.FileOutputStream
import java.io.PrintStream
import scala.util.Properties
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = args.toList match {
    case "--scalafmt-classpath" :: cp :: Nil =>
      // FIXME(gabro): this is vscode specific (at least the name)
      val workspace = System.getProperty("vscode.workspace")
      val cwd = AbsolutePath(workspace)
      val scalafmt = Formatter.classloadScalafmt(cp)
      val server =
        new ScalametaLanguageServer(cwd, System.in, System.out, scalafmt)

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
    case _ =>
      System.err.println("Missing --scalafmt-classpath")
      System.exit(1)
  }
}
