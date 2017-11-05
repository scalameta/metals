package scala.meta.languageserver

import scala.language.reflectiveCalls

import java.io.File
import java.net.URL
import java.nio.file.Paths
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import langserver.core.Connection
import langserver.messages.MessageType

abstract class Formatter {
  def format(code: String, configFile: String, filename: String): String
}
object Formatter {
  def load(args: List[String], connection: Connection): Option[Formatter] =
    args match {
      case "--scalafmt-config" :: cp :: _ =>
        Some(classloadScalafmt(cp))
      case _ =>
        connection.showMessage(MessageType.Info,
                               "Missing --scalafmt-classpath to run scalafmt")
        None
    }
  def classloadScalafmt(classpath: String): Formatter = {
    type Scalafmt210 = {
      def format(code: String, configFile: String, filename: String): String
    }
    val urls = classpath
      .split(File.pathSeparator)
      .map(file => Paths.get(file).toUri.toURL)
    val classloader = new URLClassLoader(urls, null)
    val scalafmt210 = classloader
      .loadClass("org.scalafmt.cli.Scalafmt210")
      .newInstance()
      .asInstanceOf[Scalafmt210]
    new Formatter {
      override def format(code: String,
                          configFile: String,
                          filename: String): String =
        scalafmt210.format(code, configFile, filename)
    }
  }
}
