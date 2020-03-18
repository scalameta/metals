package scala.meta.internal.metals

import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolDocumentation
import java.{util => ju}
import org.eclipse.lsp4j.Location
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.pc.SymbolSearch.Result

import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.Mtags
import scala.collection.concurrent.TrieMap

class RamboSymbolSearch(
    workspace: AbsolutePath,
    buffers: Buffers
) extends SymbolSearch {

  private val dependencySourceCache =
    new TrieMap[AbsolutePath, ju.List[String]]()

  private val scalaVersion = BuildInfo.scala212

  private val jars = Embedded
    .downloadScalaSources(scalaVersion)
    .map(path => AbsolutePath(path))

  private val (sources, classpath) =
    jars.partition(_.toString.endsWith("-sources.jar"))

  private val scalaDependency =
    ClasspathSearch.fromClasspath(classpath.toList.map(_.toNIO))

  private val index = OnDemandSymbolIndex()
  sources.foreach(index.addSourceJar)

  private val docs = new Docstrings(index)
  private val mtags = new Mtags()
  private val destinationProvider =
    new DestinationProvider(
      index,
      buffers,
      mtags,
      workspace,
      semanticdbsFallback = None
    )

  def documentation(symbol: String): ju.Optional[SymbolDocumentation] =
    docs.documentation(symbol)

  def definition(x: String): ju.List[Location] = {
    destinationProvider
      .fromSymbol(x)
      .flatMap(_.toResult)
      .map(_.locations)
      .getOrElse(ju.Collections.emptyList())
  }

  def definitionSourceToplevels(sym: String): ju.List[String] =
    index
      .definition(Symbol(sym))
      .map { symDef =>
        val input = symDef.path.toInput
        dependencySourceCache.getOrElseUpdate(
          symDef.path,
          mtags.toplevels(input).asJava
        )
      }
      .getOrElse(ju.Collections.emptyList())

  def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): Result =
    scalaDependency.search(WorkspaceSymbolQuery.exact(query), visitor)
}
