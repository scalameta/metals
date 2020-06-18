package bench

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch

import org.eclipse.lsp4j.CompletionList
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import tests.Library
import tests.TestingSymbolSearch

@State(Scope.Benchmark)
abstract class CompletionBench {
  var libraries: List[Library] = Nil
  var completions: Map[String, SourceCompletion] = Map.empty

  def runSetup(): Unit

  def presentationCompiler(): PresentationCompiler

  @Setup
  def setup(): Unit = {
    runSetup()
  }

  def downloadLibraries(): Unit = {
    libraries = Library.jdk :: Library.all
    val akka = Corpus.akka()
    val replicator =
      "akka-2.5.19/akka-cluster/src/main/scala/akka/cluster/ClusterDaemon.scala"
    val scala = Corpus.scala()
    val typers =
      s"scala-${bench.BuildInfo.scalaVersion}/src/compiler/scala/tools/nsc/typechecker/Typers.scala"
    val fastparse = Corpus.fastparse()
    val exprs =
      "fastparse-2.1.0/scalaparse/src/scalaparse/Exprs.scala"
    completions = Map(
      "scopeOpen" -> SourceCompletion.fromPath(
        "A.scala",
        """
          |import Java
          |import scala.collection.mutable
          |        """.stripMargin,
        "import Java@@"
      ),
      "scopeDeep" -> SourceCompletion.fromZipPath(
        akka,
        replicator,
        "val nonSensitiveKeys = Join@@ConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.settings)"
      ),
      "memberDeep" -> SourceCompletion.fromZipPath(
        akka,
        replicator,
        "val nonSensitiveKeys = JoinConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.@@settings)"
      ),
      "scopeTypers" -> SourceCompletion.fromZipPath(
        scala,
        typers,
        "if (argProtos.isDefinedAt(id@@x)) argProtos(idx) else NoType"
      ),
      "memberTypers" -> SourceCompletion.fromZipPath(
        scala,
        typers,
        "if (argProtos.isDefi@@nedAt(idx)) argProtos(idx) else NoType"
      ),
      "scopeFastparse" -> SourceCompletion.fromZipPath(
        fastparse,
        exprs,
        "def InfixPattern = P( S@@implePattern ~ (Id ~/ SimplePattern).rep | `_*` )"
      ),
      "memberFastparse" -> SourceCompletion.fromZipPath(
        fastparse,
        exprs,
        "def CaseClause: P[Unit] = P( `case` ~ !(`class` | `object`) ~/ Pattern ~ ExprCtx.Gua@@rd.? ~ `=>` ~ CaseBlock  )"
      )
    )
  }
  @Param(
    Array("scopeOpen", "scopeDeep", "memberDeep", "scopeTypers", "memberTypers",
      "scopeFastparse", "memberFastparse")
  )
  var completion: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def complete(): CompletionList = {
    val pc = presentationCompiler()
    val result = currentCompletion.complete(pc)
    result
  }

  def currentCompletion: SourceCompletion = completions(completion)

  def classpath: List[Path] =
    libraries.flatMap(_.classpath.entries.map(_.toNIO))
  def sources: List[AbsolutePath] = libraries.flatMap(_.sources.entries)

  def newSearch(): SymbolSearch = {
    require(libraries.nonEmpty)
    new TestingSymbolSearch(ClasspathSearch.fromClasspath(classpath))
  }

  def newPC(search: SymbolSearch = newSearch()): PresentationCompiler = {
    new ScalaPresentationCompiler()
      .withSearch(search)
      .newInstance("", classpath.asJava, Nil.asJava)
  }

  def scopeComplete(pc: PresentationCompiler): CompletionList = {
    val code = "import Java\n"
    pc.complete(
      CompilerOffsetParams(
        URI.create("file://A.scala"),
        code,
        code.length - 2
      )
    ).get()
  }
}

class CachedSearchAndCompilerCompletionBench extends CompletionBench {
  var pc: PresentationCompiler = _

  override def runSetup(): Unit = {
    downloadLibraries()
    pc = newPC()
  }

  override def presentationCompiler(): PresentationCompiler = pc
}
