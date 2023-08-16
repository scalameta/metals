package bench

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.CompilerSyntheticDecorationsParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.InlayHintsProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
class InlayHintsBench extends PcBenchmark {
  var inlayHintsRequests: Map[String, CodeWithRanges] = Map.empty
  val userConfig: UserConfiguration = UserConfiguration().copy(
    showInferredType = Some("true"),
    showImplicitArguments = true,
    showImplicitConversionsAndClasses = true,
  )

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

  @Setup
  override def setup(): Unit = {
    beforeAll()
    pc = newPC()
    val buffers = Buffers()
    val buildTargets = BuildTargets.empty
    val selector =
      new ScalaVersionSelector(
        () => UserConfiguration(fallbackScalaVersion = Some(pc.scalaVersion())),
        buildTargets,
      )
    implicit val reports = EmptyReportContext
    trees = new Trees(buffers, selector)
    inlayHintsRequests.foreach { request =>
      val path = URI.create(s"file:///${request._1}").toAbsolutePath
      buffers.put(
        path,
        request._2.code,
      )
      trees.didChange(path)
    }
  }

  @Param(
    Array("ClusterDaemon.scala", "Typers.scala", "Exprs.scala")
  )
  var currentInlayHintsRequest: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def inlayHints(): Unit = {
    val pc = presentationCompiler()
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
      val pos =
        Position.Range(Input.File(uri.toAbsolutePath), range._1, range._2)
      val inlayHintsProvider =
        new InlayHintsProvider(rangeParams, trees, () => userConfig, pos)
      val withoutTypes = inlayHintsProvider.withoutTypes
      val pcParams = CompilerSyntheticDecorationsParams(
        rangeParams,
        withoutTypes.asJava,
        true,
        true,
        true,
      )
      val nodes = pc.syntheticDecorations(pcParams).get().asScala.toList
      inlayHintsProvider.provide(
        nodes
      )
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
