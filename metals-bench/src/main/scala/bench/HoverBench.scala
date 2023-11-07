package bench

import java.util.concurrent.TimeUnit

import scala.meta.pc.HoverSignature

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
class HoverBench extends PcBenchmark {

  var requests: Map[String, SourceRequest] = Map.empty

  def beforeAll(): Unit = {

    requests = Map(
      "scopeOpen" -> SourceRequest.fromPath(
        "A.scala",
        """|package a
           |
           |object Main extends App{
           |  
           |}
           |""".stripMargin,
        "object Main extends Ap@@p",
      )
    )
  }

  @Param(Array("scopeOpen"))
  var currentRequest: String = _

  @Param(Array("3.3.1", "2.13.12"))
  var scalaVersion: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def hover(): HoverSignature = {
    val pc = presentationCompiler(scalaVersion)
    currentHover.hover(pc).get()
  }

  def currentHover: SourceRequest = requests(
    currentRequest
  )

}
