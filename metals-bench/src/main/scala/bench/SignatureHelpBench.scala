package bench

import java.util.concurrent.TimeUnit

import org.eclipse.lsp4j.SignatureHelp
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
class SignatureHelpBench extends PcBenchmark {

  var requests: Map[String, SourceRequest] = Map.empty

  def beforeAll(): Unit = {
    requests = Map(
      "basic" -> SourceRequest.fromPath(
        "A.scala",
        """|package a
           |
           |object Main{
           |  def foo(a: Int, bab: String, dbl: Double = 1.0)
           |  foo(a = 12,)
           |}
           |""".stripMargin,
        "  foo(a = 12,@@)",
      )
    )
  }

  @Param(Array("basic"))
  var currentRequest: String = _

  @Param(Array("3.3.1", "2.13.12"))
  var scalaVersion: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def signatureHelp(): SignatureHelp = {
    val pc = presentationCompiler(scalaVersion)
    currentSignatureHelp.signatureHelp(pc)
  }

  def currentSignatureHelp: SourceRequest = requests(
    currentRequest
  )

}
