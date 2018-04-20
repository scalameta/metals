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
    .head
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
    Classpath(synthetics :: classpath.shallow.map { entry =>
      if (entry.isDirectory) entry
      else classpaths.computeIfAbsent(entry, processEntry)
    })
  }

}

object ClasspathOps {

  def bootClasspath: Option[Classpath] = sys.props.collectFirst {
    case (k, v) if k.endsWith(".boot.class.path") => Classpath(v)
  }

  val devNull = new PrintStream(new OutputStream {
    override def write(b: Int): Unit = ()
  })

  /** Process classpath with metacp to build semanticdbs of global symbols. **/
  def toMetaClasspath(
      sclasspath: Classpath,
      cacheDirectory: Option[AbsolutePath] = None,
      parallel: Boolean = true,
      out: PrintStream = devNull
  ): Option[Classpath] = {
    val (processed, toProcess) = sclasspath.shallow.partition { path =>
      path.isDirectory &&
      path.resolve("META-INF").resolve("semanticdb.semanticidx").isFile
    }
    val withJDK = Classpath(
      bootClasspath.fold(sclasspath.shallow)(_.shallow ::: toProcess)
    )
    val default = metacp.Settings()
    val settings = default
      .withClasspath(withJDK)
      .withScalaLibrarySynthetics(true)
      .withCacheDir(cacheDirectory.getOrElse(default.cacheDir))
      .withPar(parallel)
    val reporter = metacp
      .Reporter()
      .withOut(devNull) // out prints classpath of proccessed classpath, which is not relevant for scalafix.
      .withErr(out)
    val mclasspath = scala.meta.cli.Metacp.process(settings, reporter)
    mclasspath.map(x => Classpath(x.shallow ++ processed))
  }

  def newSymbolTable(
      classpath: Classpath,
      cacheDirectory: Option[AbsolutePath] = None,
      parallel: Boolean = true,
      out: PrintStream = System.out
  ): Option[SymbolTable] = {
    toMetaClasspath(classpath, cacheDirectory, parallel, out)
      .map(new LazySymbolTable(_))
  }

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
