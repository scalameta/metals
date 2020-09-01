package scala.meta.internal.metals

import java.nio.file.Path
import java.{util => ju}

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearch.Result
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j.Location

class StandaloneSymbolSearch(
    workspace: AbsolutePath,
    classpath: Seq[AbsolutePath],
    sources: Seq[AbsolutePath],
    buffers: Buffers,
    isExcludedPackage: String => Boolean,
    workspaceFallback: Option[SymbolSearch] = None
) extends SymbolSearch {

  private val dependencySourceCache =
    new TrieMap[AbsolutePath, ju.List[String]]()
  private val classpathSearch =
    ClasspathSearch.fromClasspath(
      classpath.toList.map(_.toNIO),
      isExcludedPackage
    )

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
    docs
      .documentation(symbol)
      .asScala
      .orElse(workspaceFallback.flatMap(_.documentation(symbol).asScala))
      .asJava

  override def definition(x: String): ju.List[Location] = {
    destinationProvider
      .fromSymbol(x)
      .flatMap(_.toResult)
      .map(_.locations)
      .orElse(workspaceFallback.map(_.definition(x)))
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
      .orElse(workspaceFallback.map(_.definitionSourceToplevels(sym)))
      .getOrElse(ju.Collections.emptyList())

  def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): Result = {
    val res = classpathSearch.search(WorkspaceSymbolQuery.exact(query), visitor)
    workspaceFallback
      .map(_.search(query, buildTargetIdentifier, visitor))
      .getOrElse(res)
  }
}

object StandaloneSymbolSearch {

  def apply(
      workspace: AbsolutePath,
      buffers: Buffers,
      sources: Seq[Path],
      classpath: Seq[Path],
      isExcludedPackage: String => Boolean
  ): StandaloneSymbolSearch = {
    new StandaloneSymbolSearch(
      workspace,
      classpath.map(path => AbsolutePath(path)),
      sources.map(path => AbsolutePath(path)),
      buffers,
      isExcludedPackage
    )
  }

  def apply(
      workspace: AbsolutePath,
      buffers: Buffers,
      isExcludedPackage: String => Boolean
  ): StandaloneSymbolSearch = {
    val scalaVersion = BuildInfo.scala212

    val jars = Embedded
      .downloadScalaSources(scalaVersion)
      .map(path => AbsolutePath(path))

    val (sources, classpath) =
      jars.partition(_.toString.endsWith("-sources.jar"))

    new StandaloneSymbolSearch(
      workspace,
      classpath,
      sources,
      buffers,
      isExcludedPackage
    )
  }
}
