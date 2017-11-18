package scala.meta.languageserver

import scala.language.reflectiveCalls

import java.io.PrintStream
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import com.typesafe.scalalogging.LazyLogging

abstract class Formatter {
  def format(code: String, configFile: String, filename: String): String
}
object Formatter extends LazyLogging {
  def empty: Formatter = new Formatter {
    override def format(
        code: String,
        configFile: String,
        filename: String
    ): String = code
  }
  def classloadScalafmt(version: String): Formatter = {
    val urls = Jars
      .fetch("com.geirsson", "scalafmt-cli_2.12", version, System.out)
      .iterator
      .map(_.toURI.toURL)
      .toArray
    logger.info(s"Classloading scalafmt with ${urls.length} downloaded jars")
    type Scalafmt210 = {
      def format(code: String, configFile: String, filename: String): String
    }
    val classloader = new URLClassLoader(urls, null)
    val scalafmt210 = classloader
      .loadClass("org.scalafmt.cli.Scalafmt210")
      .newInstance()
      .asInstanceOf[Scalafmt210]
    new Formatter {
      override def format(
          code: String,
          configFile: String,
          filename: String
      ): String =
        scalafmt210.format(code, configFile, filename)
    }
  }
}
