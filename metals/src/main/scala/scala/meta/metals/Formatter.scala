package scala.meta.metals

import scala.language.reflectiveCalls

import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import org.langmeta.io.AbsolutePath

abstract class Formatter {

  /** Format using configuration from configFile */
  def format(code: String, filename: String, configFile: AbsolutePath): String

  /** Format using default settings */
  def format(code: String, filename: String): String

}

object Formatter {

  /** Returns formatter that does nothing */
  lazy val noop: Formatter = new Formatter {
    override def format(
        code: String,
        filename: String,
        configFile: AbsolutePath
    ): String = code
    override def format(
        code: String,
        filename: String
    ): String = code
  }

  /** Returns instance of scalafmt from isoloated classloader */
  def classloadScalafmt(version: String): Formatter = {
    val urls = Jars
      .fetch("com.geirsson", "scalafmt-cli_2.12", version, System.out)
      .iterator
      .map(_.toURI.toURL)
      .toArray
    scribe.info(s"Classloading scalafmt with ${urls.length} downloaded jars")
    type Scalafmt210 = {
      def format(code: String, configFile: String, filename: String): String
      def format(code: String, filename: String): String
    }
    val classloader = new URLClassLoader(urls, null)
    val scalafmt210 = classloader
      .loadClass("org.scalafmt.cli.Scalafmt210")
      .newInstance()
      .asInstanceOf[Scalafmt210]
    new Formatter {
      override def format(
          code: String,
          filename: String,
          configFile: AbsolutePath
      ): String =
        scalafmt210.format(code, configFile.toString(), filename)

      override def format(code: String, filename: String): String =
        scalafmt210.format(code, filename)
    }
  }

}
