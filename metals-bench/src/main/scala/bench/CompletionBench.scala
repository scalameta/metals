package bench

import java.util.concurrent.TimeUnit

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

  var completionRequests: Map[String, SourceRequest] = Map.empty

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
      "scopeOpen" -> SourceRequest.fromPath(
        "A.scala",
        """|
           |object Main{
           |  def foo = ""
           |  fo
           |}
           |""".stripMargin,
        "  fo@@",
      ),
      "scopeDeep" -> SourceRequest.fromZipPath(
        akka,
        replicator,
        "val nonSensitiveKeys = Join@@ConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.settings)",
      ),
      "memberDeep" -> SourceRequest.fromZipPath(
        akka,
        replicator,
        "val nonSensitiveKeys = JoinConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.@@settings)",
      ),
      "scopeTypers" -> SourceRequest.fromZipPath(
        scala,
        typers,
        "if (argProtos.isDefinedAt(id@@x)) argProtos(idx) else NoType",
      ),
      "memberTypers" -> SourceRequest.fromZipPath(
        scala,
        typers,
        "if (argProtos.isDefi@@nedAt(idx)) argProtos(idx) else NoType",
      ),
      "scopeFastparse" -> SourceRequest.fromZipPath(
        fastparse,
        exprs,
        "def InfixPattern = P( S@@implePattern ~ (Id ~/ SimplePattern).rep | `_*` )",
      ),
      "memberFastparse" -> SourceRequest.fromZipPath(
        fastparse,
        exprs,
        "def CaseClause: P[Unit] = P( `case` ~ !(`class` | `object`) ~/ Pattern ~ ExprCtx.Gua@@rd.? ~ `=>` ~ CaseBlock  )",
      ),
    )
  }

  // to test different scenarios use the values from completionRequests
  @Param(Array("scopeOpen"))
  var currentCompletionRequest: String = _

  @Param(Array("3.3.1", "2.13.12"))
  var scalaVersion: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def complete(): CompletionList = {
    val pc = presentationCompiler(scalaVersion)
    val result = currentCompletion.complete(pc)
    result
  }

  def currentCompletion: SourceRequest = completionRequests(
    currentCompletionRequest
  )

}
