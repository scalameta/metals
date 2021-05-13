package bench

import java.util.concurrent.TimeUnit

import scala.meta.dialects
import scala.meta.internal.metals.WorkspaceSymbolProvider

import org.eclipse.lsp4j.SymbolInformation
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import tests.MetalsTestEnrichments._
import tests.TestingWorkspaceSymbolProvider

@State(Scope.Benchmark)
class WorkspaceFuzzBench {
  var symbols: WorkspaceSymbolProvider = _

  @Setup
  def setup(): Unit = {
    symbols = TestingWorkspaceSymbolProvider(Corpus.akka())
    symbols.indexWorkspace(dialects.Scala213)
  }

  @Param(
    Array("FSM", "Actor", "Actor(", "FSMFB", "ActRef", "actorref", "actorrefs",
      "fsmbuilder", "fsmfunctionbuilder", "abcdefghijklmnopqrstabcdefghijkl")
  )
  var query: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def upper(): Seq[SymbolInformation] = {
    symbols.search(query)
  }

}
