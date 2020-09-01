package bench

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.ExcludedPackagesHandler

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import tests.Library

@State(Scope.Benchmark)
class ClasspathIndexingBench {
  var classpath: Seq[Path] = _

  @Setup
  def setup(): Unit = {
    classpath = Library.all.flatMap(_.classpath.entries.map(_.toNIO))
  }

  @TearDown
  def teardown(): Unit = {}

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def run(): Unit = {
    ClasspathSearch.fromClasspath(
      classpath,
      new ExcludedPackagesHandler(None).isExcludedPackage
    )
  }

}
