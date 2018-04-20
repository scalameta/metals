package scala.meta.metals.compiler

import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util
import java.util.Collections
import java.util.function
import scala.meta.AbsolutePath
import scala.meta.Classpath
import scala.meta.cli.Metacp
import scala.meta.metacp
import scala.meta.metacp.Reporter
import scala.meta.metacp.Settings
import scalafix.internal.util.LazySymbolTable
import scalafix.internal.util.SymbolTable

class MetacpProvider {
  private val classpaths =
    Collections.synchronizedMap(new util.HashMap[AbsolutePath, AbsolutePath]())
  private val settings =
    Settings().withPar(false).withScalaLibrarySynthetics(false)
  private val reporter = Reporter()
  private val synthetics = Metacp
    .process(Settings().withScalaLibrarySynthetics(true), reporter)
    .get
    .shallow
  private val jdk = Metacp
    .process(settings.withClasspath(ClasspathOps.bootClasspath), reporter)
    .getOrElse {
      throw new IllegalArgumentException("Failed to process JDK")
    }
  private val empty = AbsolutePath(Files.createTempDirectory("metals"))
  private val processEntry = new function.Function[AbsolutePath, AbsolutePath] {
    override def apply(t: AbsolutePath): AbsolutePath = {
      val result = Metacp.process(
        settings.withClasspath(Classpath(t :: Nil)),
        reporter
      )
      result match {
        case Some(Classpath(entry :: Nil)) =>
          entry
        case _ =>
          empty
      }
    }
  }

  def process(classpath: Classpath): Classpath = {
    val processed = classpath.shallow.map { entry =>
      if (entry.isDirectory) entry
      else classpaths.computeIfAbsent(entry, processEntry)
    }
    Classpath(List(synthetics, jdk.shallow, processed).flatten)
  }

}

object ClasspathOps {

  def bootClasspath: Classpath =
    sys.props
      .collectFirst {
        case (k, v) if k.endsWith(".boot.class.path") =>
          Classpath(
            Classpath(v).shallow.filter(p => Files.exists(p.toNIO))
          )
      }
      .getOrElse(Classpath(Nil))

  def getCurrentClasspath: Classpath = {
    Thread.currentThread.getContextClassLoader match {
      case url: java.net.URLClassLoader =>
        val classpath = url.getURLs.map(_.getFile).mkString(File.pathSeparator)
        Classpath(classpath)
      case els =>
        throw new IllegalStateException(
          s"Expected java.net.URLClassLoader, obtained $els"
        )
    }
  }
}
