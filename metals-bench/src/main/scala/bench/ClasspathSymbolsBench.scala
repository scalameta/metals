package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import scala.meta.internal.tvp.ClasspathSymbols
import scala.meta.io.AbsolutePath
import tests.Library

@State(Scope.Benchmark)
class ClasspathSymbolsBench {
  var classpath: Seq[AbsolutePath] = _

  @Setup
  def setup(): Unit = {
    classpath = Library.cats
  }

  @TearDown
  def teardown(): Unit = {}

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def run(): Unit = {
    val jars = new ClasspathSymbols()
    classpath.foreach { jar =>
      jars.symbols(jar, "cats/")
    }
  }

}
