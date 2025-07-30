package bench

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.CompilerInlayHintsParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
class InlayHintsBench extends PcBenchmark {
  var inlayHintsRequests: Map[String, CodeWithRanges] = Map.empty

  private def fromZipPath(zip: AbsolutePath, path: String) = {
    FileIO.withJarFileSystem(zip, create = false, close = true)(root =>
      FileIO.slurp(root.resolve(path), StandardCharsets.UTF_8)
    )
  }

  var trees: Trees = _
  def beforeAll(): Unit = {
    val akka = Corpus.akka()
    val replicator =
      "akka-2.5.19/akka-cluster/src/main/scala/akka/cluster/ClusterDaemon.scala"
    val scala = Corpus.scala()
    val typers =
      s"scala-${bench.BuildInfo.scalaVersion}/src/compiler/scala/tools/nsc/typechecker/Typers.scala"
    val fastparse = Corpus.fastparse()
    val exprs =
      "fastparse-2.1.0/scalaparse/src/scalaparse/Exprs.scala"

    inlayHintsRequests = Map(
      "ClusterDaemon.scala" -> CodeWithRanges(
        fromZipPath(
          akka,
          replicator,
        )
      ),
      "Typers.scala" -> CodeWithRanges(
        fromZipPath(
          scala,
          typers,
        )
      ),
      "Exprs.scala" -> CodeWithRanges(
        fromZipPath(
          fastparse,
          exprs,
        )
      ),
    )
  }
  @Param(
    Array("ClusterDaemon.scala", "Typers.scala", "Exprs.scala")
  )
  var currentInlayHintsRequest: String = _

  @Param(Array("3.3.1", "2.13.12"))
  var scalaVersion: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def inlayHints(): Unit = {
    val pc = presentationCompiler(scalaVersion)
    val text = currentInlayHints.code
    val uri = URI.create(s"file:///${currentInlayHintsRequest}")
    for {
      range <- currentInlayHints.ranges
    } yield {
      val rangeParams = CompilerRangeParams(
        uri,
        text,
        range._1,
        range._2,
        EmptyCancelToken,
      )
      val pcParams = CompilerInlayHintsParams(
        rangeParams,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        false,
      )
      pc.inlayHints(pcParams).get().asScala.toList
    }
  }

  def currentInlayHints: CodeWithRanges = inlayHintsRequests(
    currentInlayHintsRequest
  )

  case class CodeWithRanges(
      code: String,
      ranges: List[(Int, Int)],
  )

  object CodeWithRanges {
    def apply(code: String): CodeWithRanges = {
      val sliceLengths =
        code.split("\n", -1).grouped(300).map(_.map(_.length).sum)
      val ranges = sliceLengths.foldLeft(List.empty[(Int, Int)]) {
        case (acc, sliceLength) =>
          val start = acc.lastOption.map(_._2).getOrElse(0)
          val end = start + sliceLength
          acc :+ (start, end)
      }
      CodeWithRanges(code, ranges)
    }
  }

}
