package bench

import java.util.concurrent.TimeUnit

import scala.meta.pc.DefinitionResult

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
class DefinitionBench extends PcBenchmark {

  var requests: Map[String, SourceRequest] = Map.empty

  def beforeAll(): Unit = {
    requests = Map(
      "basic" -> SourceRequest.fromPath(
        "A.scala",
        """|package a
           |
           |object Main{
           |  def foo(a: Int, bab: String, dbl: Double = 1.0)
           |  foo(1, "")
           |}
           |""".stripMargin,
        "  f@@oo(1, \"\")",
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
  def definition(): DefinitionResult = {
    val pc = presentationCompiler(scalaVersion)
    currentDefinition.definition(pc)
  }

  def currentDefinition: SourceRequest = requests(
    currentRequest
  )

}
