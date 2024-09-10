package bench

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import scala.meta.internal.io.FileIO
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.SemanticTokensProvider
import scala.meta.io.AbsolutePath

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import tests.MetalsTestEnrichments

@State(Scope.Benchmark)
class SemanticTokensBench extends PcBenchmark {
  var highlightRequests: Map[String, String] = Map.empty

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

    highlightRequests = Map(
      "A.scala" ->
        """
          |import Java
          |import scala.collection.mutable
          |        """.stripMargin,
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
    Array("A.scala", "ClusterDaemon.scala", "Typers.scala", "Exprs.scala")
  )
  var currentHighlightRequest: String = _

  @Param(Array("3.3.1", "2.13.12"))
  var scalaVersion: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def semanticHighlight(): List[Integer] = {
    val pc = presentationCompiler(scalaVersion)
    val text = currentHighlight
    val vFile = CompilerVirtualFileParams(
      URI.create(s"file://${currentHighlightRequest}"),
      text,
      EmptyCancelToken,
    )

    val nodes = pc.semanticTokens(vFile).get().asScala.toList
    val isScala3 = ScalaVersions.isScala3Version(pc.scalaVersion())
    SemanticTokensProvider.provide(
      nodes,
      vFile,
      AbsolutePath(Paths.get(vFile.uri)),
      isScala3,
      MetalsTestEnrichments.emptyTrees,
    )(EmptyReportContext)
  }

  def currentHighlight: String = highlightRequests(currentHighlightRequest)

}
