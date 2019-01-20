package bench

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsLogger
import scala.meta.io.AbsolutePath
import tests.TestingClient

@State(Scope.Benchmark)
class ServerInitializeBench {

  // Replace with path to local directory that you want to benchmark.
  @Param(Array("/Users/olafurpg/dev/prisma/server"))
  var workspace: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def run(): Unit = {
    val path = AbsolutePath(workspace)
    val buffers = Buffers()
    val client = new TestingClient(path, buffers)
    MetalsLogger.updateDefaultFormat()
    var server: MetalsLanguageServer = _
    val ex = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutorService(ex)
    server = new MetalsLanguageServer(ec)
    server.connectToLanguageClient(client)
    val initialize = new InitializeParams
    initialize.setRootUri(path.toURI.toString)
    server.initialize(initialize).get()
    server.initialized(new InitializedParams).get()
    server.shutdown().get()
  }

}
