package bench

import scala.collection.JavaConverters._
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.CompletionItems
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import tests.Library
import tests.TestingSymbolSearch
import tests.TestingWorkspaceSearch

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
    val akka = AkkaSources.download()
    val replicator =
      "akka-2.5.19/akka-cluster/src/main/scala/akka/cluster/ClusterDaemon.scala"
    completions = Map(
      "scopeOpen" -> SourceCompletion.fromPath(
        "A.scala",
        """
          |import Java
          |import scala.collection.mutable
          |        """.stripMargin,
        "import Java@@"
      ),
      "scopeDeep" -> SourceCompletion.fromAkkaPath(
        akka,
        replicator,
        "val nonSensitiveKeys = Join@@ConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.settings)"
      ),
      "memberDeep" -> SourceCompletion.fromAkkaPath(
        akka,
        replicator,
        "val nonSensitiveKeys = JoinConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.@@settings)"
      )
    )
  }
  @Param(Array("scopeOpen", "scopeDeep", "memberDeep"))
  var completion: String = _

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def complete(): CompletionItems = {
    val pc = presentationCompiler()
    val result = currentCompletion.complete(pc)
    val diagnostics = pc.diagnostics()
    require(diagnostics.isEmpty, diagnostics.asScala.mkString("\n", "\n", "\n"))
    result
  }

  def currentCompletion: SourceCompletion = completions(completion)

  def classpath: List[Path] =
    libraries.flatMap(_.classpath.entries.map(_.toNIO))
  def sources: List[AbsolutePath] = libraries.flatMap(_.sources.entries)

  def newSearch(): SymbolSearch = {
    require(libraries.nonEmpty)
    new TestingSymbolSearch(ClasspathSearch.fromClasspath(classpath, _ => 0))
  }

  def newPC(search: SymbolSearch = newSearch()): PresentationCompiler = {
    new ScalaPresentationCompiler()
      .withSearch(search)
      .newInstance("", classpath.asJava, Nil.asJava)
  }

  def scopeComplete(pc: PresentationCompiler): CompletionItems = {
    val code = "import Java\n"
    pc.complete(CompilerOffsetParams("A.scala", code, code.length - 2))
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
