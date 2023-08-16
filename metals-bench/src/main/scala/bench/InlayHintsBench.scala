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
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.InlayHintsProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.InlayHint
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
class InlayHintsBench extends PcBenchmark {
  var inlayHintsRequests: Map[String, String] = Map.empty
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
      "ClusterDaemon.scala" -> fromZipPath(
        akka,
        replicator,
      ),
      "Typers.scala" -> fromZipPath(
        scala,
        typers,
      ),
      "Exprs.scala" -> fromZipPath(
        fastparse,
        exprs,
      ),
    )
  }

  @Param(
    Array("ClusterDaemon.scala", "Typers.scala", "Exprs.scala")
  )
  var currentInlayHintsRequest: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def inlayHints(): List[InlayHint] = {
    val pc = presentationCompiler()
    val text = currentInlayHints
    val uri = URI.create(s"file:///${currentInlayHintsRequest}")
    val params = CompilerRangeParams(
      uri,
      text,
      0,
      text.length,
      EmptyCancelToken,
    )
    val trees = getTrees(pc.scalaVersion(), uri.toAbsolutePath, text)
    val pos = Position.Range(Input.File(uri.toAbsolutePath), 0, text.length)
    val nodes = pc.syntheticDecorations(params).get().asScala.toList
    new InlayHintsProvider(params, trees, () => userConfig, pos).provide(
      nodes
    )
  }

  def currentInlayHints: String = inlayHintsRequests(currentInlayHintsRequest)

  private def getTrees(
      scalaVersion: String,
      path: AbsolutePath,
      text: String,
  ): Trees = {
    val buffers = Buffers()
    buffers.put(path, text)
    val buildTargets = BuildTargets.empty
    val selector =
      new ScalaVersionSelector(
        () => UserConfiguration(fallbackScalaVersion = Some(scalaVersion)),
        buildTargets,
      )
    implicit val reports = EmptyReportContext
    new Trees(buffers, selector)
  }

}
