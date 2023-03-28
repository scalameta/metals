package bench

import java.nio.file.Path

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch

import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.TearDown
import tests.Library
import tests.TestingSymbolSearch

abstract class PcBenchmark {

  var pc: PresentationCompiler = _

  def presentationCompiler(): PresentationCompiler = pc

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

  def newPC(search: SymbolSearch = newSearch()): PresentationCompiler = {
    new ScalaPresentationCompiler()
      .withSearch(search)
      .newInstance("", classpath.asJava, Nil.asJava)
  }

  @Setup
  def setup(): Unit = {
    beforeAll()
    pc = newPC()
  }

  @TearDown
  def tearDown(): Unit = {
    presentationCompiler().shutdown()
  }

}
