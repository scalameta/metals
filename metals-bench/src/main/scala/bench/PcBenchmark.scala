package bench

import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContextExecutor

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch

import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.TearDown
import tests.Library
import tests.TestingSymbolSearch

abstract class PcBenchmark {

  protected val presentationCompilers: TrieMap[String, PresentationCompiler] =
    TrieMap.empty[String, PresentationCompiler]
  protected implicit val ec: ExecutionContextExecutor =
    scala.concurrent.ExecutionContext.global
  protected val embedded = new Embedded(
    new metals.WorkDoneProgress(NoopLanguageClient, metals.Time.system)
  )

  protected final val benchmarkedScalaVersions: Array[String] = Array(
    metals.BuildInfo.scala3,
    metals.BuildInfo.scala213,
  )

  def presentationCompiler(version: String): PresentationCompiler = {
    presentationCompilers.getOrElseUpdate(
      version,
      newPC(version, newSearch()),
    )
  }

  def beforeAll(): Unit

  def libraries: List[Library] = Library.jdk :: Library.allScala2

  def classpath: List[Path] =
    libraries.flatMap(_.classpath.entries.map(_.toNIO))

  def sources: List[AbsolutePath] = libraries.flatMap(_.sources.entries)

  def newSearch(): SymbolSearch = {
    require(libraries.nonEmpty)
    new TestingSymbolSearch(
      ClasspathSearch.fromClasspath(
        classpath,
        ExcludedPackagesHandler.default,
      )
    )
  }

  def newPC(
      version: String,
      search: SymbolSearch = newSearch(),
  ): PresentationCompiler = {
    val pc = MtagsResolver.default().resolve(version) match {
      case Some(MtagsBinaries.BuildIn) => new ScalaPresentationCompiler()
      case Some(artifacts: MtagsBinaries.Artifacts) =>
        embedded.presentationCompiler(artifacts)
      case _ =>
        throw new RuntimeException(
          s"Forgot to release scala version: $version ?"
        )

    }
    pc.withSearch(search)
      .newInstance("", classpath.asJava, Nil.asJava, Nil.asJava)
  }

  @Setup
  def setup(): Unit = {
    beforeAll()
    benchmarkedScalaVersions.foreach(newPC(_))
  }

  @TearDown
  def tearDown(): Unit = {
    presentationCompilers.values.foreach(_.shutdown())
  }

}
