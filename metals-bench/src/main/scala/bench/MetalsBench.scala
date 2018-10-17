package bench

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.meta.metals.MetalsLogger
import tests.InputProperties
import tests.Libraries
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.SemanticdbClasspath
import tests.Library

@State(Scope.Benchmark)
class MetalsBench {

  MetalsLogger.updateFormat()
  val inputs = InputProperties.default()
  val classpath = new SemanticdbClasspath(inputs.sourceroot, inputs.classpath)
  val documents: List[(AbsolutePath, TextDocument)] =
    inputs.scalaFiles.map { input =>
      (input.file, classpath.textDocument(input.file).get)
    }

  val scalaLibrarySourcesJar: AbsolutePath = inputs.dependencySources.entries
    .find(_.toNIO.getFileName.toString.contains("scala-library"))
    .getOrElse {
      sys.error(
        s"missing: scala-library-sources.jar, obtained ${inputs.dependencySources}"
      )
    }

  val jdk = Classpath(Library.jdkSources.toList)
  val fullClasspath = jdk ++ inputs.dependencySources

  val inflated = Inflated.jars(fullClasspath)

  val scalaDependencySources: Inflated = {
    val result = inflated.filter(_.path.endsWith(".scala"))
    scribe.info(s"Scala lines: ${result.linesOfCode}")
    result
  }

  val javaDependencySources: Inflated = {
    val result = inflated.filter(_.path.endsWith(".java"))
    scribe.info(s"Java lines: ${result.linesOfCode}")
    result
  }

  val megaSources = Classpath(
    Libraries.suite
      .flatMap(_.sources().entries)
      .filter(_.toNIO.getFileName.toString.endsWith(".jar"))
  )

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalaMtags(): Unit = {
    scalaDependencySources.inputs.foreach { input =>
      Mtags.index(input)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def scalaToplevels(): Unit = {
    scalaDependencySources.inputs.foreach { input =>
      Mtags.toplevels(input)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def javaMtags(): Unit = {
    javaDependencySources.inputs.foreach { input =>
      Mtags.index(input)
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  def indexSources(): Unit = {
    val index = OnDemandSymbolIndex()
    fullClasspath.entries.foreach(entry => index.addSourceJar(entry))
  }

}
