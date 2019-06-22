package bench

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.SymbolInformation
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.io.AbsolutePath
import tests.Library
import tests.TestingWorkspaceSymbolProvider
import tests.MetalsTestEnrichments._
import org.openjdk.jmh.annotations.Level
import scala.meta.internal.metals.CompressedPackageIndex

@State(Scope.Benchmark)
class ClasspathFuzzBench {
  var symbols: WorkspaceSymbolProvider = _
  var tmp: AbsolutePath = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    tmp = AbsolutePath(Files.createTempDirectory("metals"))
    symbols = TestingWorkspaceSymbolProvider(tmp, bucketSize = bucketSize)
    symbols.indexLibraries(Library.all)
    symbols.indexClasspath()
  }

  @TearDown
  def teardown(): Unit = {
    RecursivelyDelete(tmp)
  }

  @Param(Array("InputStream", "Str", "Like", "M.E", "File", "Files"))
  var query: String = _

  // @Param(Array("256", "512", "1024", "2048", "4096", "8192"))
  var bucketSize: Int =
    // See docstring of DefaultBucketSize for results from testing different bucket sizes.
    CompressedPackageIndex.DefaultBucketSize

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def run(): Seq[SymbolInformation] = {
    symbols.search(query)
  }

}
