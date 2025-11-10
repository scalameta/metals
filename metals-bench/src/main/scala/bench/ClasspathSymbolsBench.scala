package bench

import java.util.concurrent.TimeUnit

import scala.meta.dialects
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.tvp.IndexedSymbols
import scala.meta.io.AbsolutePath

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import tests.Library
import tests.TreeUtils

@State(Scope.Benchmark)
class ClasspathSymbolsBench {
  var classpath: Seq[AbsolutePath] = _

  @Setup
  def setup(): Unit = {
    classpath = Library.catsSources.filter(_.filename.contains("sources"))
  }

  @TearDown
  def teardown(): Unit = {}

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def run(): Unit = {
    implicit val reporting = EmptyReportContext
    val (buffers, trees) = TreeUtils.getTrees(scalaVersion = None)
    val jars = new IndexedSymbols(
      isStatisticsEnabled = false,
      trees,
      buffers,
      BuildTargets.empty,
      () => Mtags.testingSingleton,
    )
    classpath.foreach { jar =>
      jars.jarSymbols(jar, "cats/", dialects.Scala213)
    }
  }

}
