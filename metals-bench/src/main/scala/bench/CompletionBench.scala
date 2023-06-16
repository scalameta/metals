package bench

import java.net.URI
import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.PresentationCompiler

import org.eclipse.lsp4j.CompletionList
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
class CompletionBench extends PcBenchmark {

  var completionRequests: Map[String, SourceCompletion] = Map.empty

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
    completionRequests = Map(
      "scopeOpen" -> SourceCompletion.fromPath(
        "A.scala",
        """
          |import Java
          |import scala.collection.mutable
          |        """.stripMargin,
        "import Java@@",
      ),
      "scopeDeep" -> SourceCompletion.fromZipPath(
        akka,
        replicator,
        "val nonSensitiveKeys = Join@@ConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.settings)",
      ),
      "memberDeep" -> SourceCompletion.fromZipPath(
        akka,
        replicator,
        "val nonSensitiveKeys = JoinConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.@@settings)",
      ),
      "scopeTypers" -> SourceCompletion.fromZipPath(
        scala,
        typers,
        "if (argProtos.isDefinedAt(id@@x)) argProtos(idx) else NoType",
      ),
      "memberTypers" -> SourceCompletion.fromZipPath(
        scala,
        typers,
        "if (argProtos.isDefi@@nedAt(idx)) argProtos(idx) else NoType",
      ),
      "scopeFastparse" -> SourceCompletion.fromZipPath(
        fastparse,
        exprs,
        "def InfixPattern = P( S@@implePattern ~ (Id ~/ SimplePattern).rep | `_*` )",
      ),
      "memberFastparse" -> SourceCompletion.fromZipPath(
        fastparse,
        exprs,
        "def CaseClause: P[Unit] = P( `case` ~ !(`class` | `object`) ~/ Pattern ~ ExprCtx.Gua@@rd.? ~ `=>` ~ CaseBlock  )",
      ),
    )
  }

  @Param(
    Array("scopeOpen", "scopeDeep", "memberDeep", "scopeTypers", "memberTypers",
      "scopeFastparse", "memberFastparse")
  )
  var currentCompletionRequest: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def complete(): CompletionList = {
    val pc = presentationCompiler()
    val result = currentCompletion.complete(pc)
    result
  }

  def currentCompletion: SourceCompletion = completionRequests(
    currentCompletionRequest
  )

  def scopeComplete(pc: PresentationCompiler): CompletionList = {
    val code = "import Java\n"
    pc.complete(
      CompilerOffsetParams(
        URI.create("file://A.scala"),
        code,
        code.length - 2,
      )
    ).get()
  }
}
