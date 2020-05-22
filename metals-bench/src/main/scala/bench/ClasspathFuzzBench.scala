package bench

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.SymbolInformation
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import tests.Library
import tests.MetalsTestEnrichments._
import tests.TestingWorkspaceSymbolProvider

@State(Scope.Benchmark)
class ClasspathFuzzBench {
  var symbols: WorkspaceSymbolProvider = _
  var tmp: AbsolutePath = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    tmp = AbsolutePath(Files.createTempDirectory("metals"))
    symbols = TestingWorkspaceSymbolProvider(tmp)
    symbols.indexLibraries(Library.all)
    symbols.indexClasspath()
  }

  @TearDown
  def teardown(): Unit = {
    RecursivelyDelete(tmp)
  }

  @Param(Array("InputStream", "Str", "Like", "M.E", "File", "Files"))
  var query: String = _
  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def run(): Seq[SymbolInformation] = {
    symbols.search(query)
  }

}
