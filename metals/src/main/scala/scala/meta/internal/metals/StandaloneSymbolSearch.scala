package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Path
import java.{util => ju}

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.ParentSymbols
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
    excludedPackages: () => ExcludedPackagesHandler,
    trees: Trees,
    buildTargets: BuildTargets,
    saveSymbolFileToDisk: Boolean,
    sourceMapper: SourceMapper,
    workspaceFallback: Option[SymbolSearch] = None,
) extends SymbolSearch {

  private val dependencySourceCache =
    new TrieMap[AbsolutePath, ju.List[String]]()
  private val classpathSearch =
    ClasspathSearch.fromClasspath(
      classpath.map(_.toNIO),
      excludedPackages(),
    )

  private val index = OnDemandSymbolIndex.empty()
  sources.foreach(s =>
    index.addSourceJar(s, ScalaVersions.dialectForDependencyJar(s.filename))
  )

  private val docs = new Docstrings(index)
  private val mtags = new Mtags()
  private val destinationProvider =
    new DestinationProvider(
      index,
      buffers,
      mtags,
      workspace,
      semanticdbsFallback = None,
      trees,
      buildTargets,
      saveSymbolFileToDisk,
      sourceMapper,
    )

  def documentation(
      symbol: String,
      parents: ParentSymbols,
  ): ju.Optional[SymbolDocumentation] =
    docs
      .documentation(symbol, parents)
      .asScala
      .orElse(
        workspaceFallback.flatMap(_.documentation(symbol, parents).asScala)
      )
      .asJava

  override def definition(x: String, source: URI): ju.List[Location] = {
    val sourcePath = Option(source).map(AbsolutePath.fromAbsoluteUri)
    destinationProvider
      .fromSymbol(x, sourcePath)
      .map(_.locations)
      .orElse(workspaceFallback.map(_.definition(x, source)))
      .getOrElse(ju.Collections.emptyList())
  }

  def definitionSourceToplevels(sym: String, source: URI): ju.List[String] = {
    val sourcePath = Option(source).map(AbsolutePath.fromAbsoluteUri)
    destinationProvider
      .definition(sym, sourcePath)
      .map { symDef =>
        val input = symDef.path.toInput
        dependencySourceCache.getOrElseUpdate(
          symDef.path,
          mtags.toplevels(input).asJava,
        )
      }
      .orElse(workspaceFallback.map(_.definitionSourceToplevels(sym, source)))
      .getOrElse(ju.Collections.emptyList())
  }

  def search(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): Result = {
    val res = classpathSearch.search(WorkspaceSymbolQuery.exact(query), visitor)
    workspaceFallback
      .map(_.search(query, buildTargetIdentifier, visitor))
      .getOrElse(res)
  }

  def searchMethods(
      query: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor,
  ): Result = {
    workspaceFallback
      .map(_.searchMethods(query, buildTargetIdentifier, visitor))
      .getOrElse(Result.COMPLETE)
  }
}

object StandaloneSymbolSearch {
  def apply(
      scalaVersion: String,
      workspace: AbsolutePath,
      buffers: Buffers,
      sources: Seq[Path],
      classpath: Seq[Path],
      excludedPackages: () => ExcludedPackagesHandler,
      userConfig: () => UserConfiguration,
      trees: Trees,
      buildTargets: BuildTargets,
      saveSymbolFileToDisk: Boolean,
      sourceMapper: SourceMapper,
  ): StandaloneSymbolSearch = {
    val (sourcesWithExtras, classpathWithExtras) =
      addScalaAndJava(
        scalaVersion,
        sources.map(AbsolutePath(_)),
        classpath.map(AbsolutePath(_)),
        userConfig().javaHome,
      )

    new StandaloneSymbolSearch(
      workspace,
      classpathWithExtras,
      sourcesWithExtras,
      buffers,
      excludedPackages,
      trees,
      buildTargets,
      saveSymbolFileToDisk,
      sourceMapper,
    )
  }
  def apply(
      scalaVersion: String,
      workspace: AbsolutePath,
      buffers: Buffers,
      excludedPackages: () => ExcludedPackagesHandler,
      userConfig: () => UserConfiguration,
      trees: Trees,
      buildTargets: BuildTargets,
      saveSymbolFileToDisk: Boolean,
      sourceMapper: SourceMapper,
  ): StandaloneSymbolSearch = {
    val (sourcesWithExtras, classpathWithExtras) =
      addScalaAndJava(scalaVersion, Nil, Nil, userConfig().javaHome)

    new StandaloneSymbolSearch(
      workspace,
      classpathWithExtras,
      sourcesWithExtras,
      buffers,
      excludedPackages,
      trees,
      buildTargets,
      saveSymbolFileToDisk,
      sourceMapper,
    )
  }

  /**
   * When creating the standalone check to see if the sources have Scala. If
   * not, we add the sources and the classpath. We also add in the JDK sources.
   *
   * @param sources the original sources that may be included.
   * @param classpath the current classpath.
   * @param javaHome possible JavaHome to get the JDK sources from.
   * @return (scalaSources, scalaClasspath)
   */
  private def addScalaAndJava(
      scalaVersion: String,
      sources: Seq[AbsolutePath],
      classpath: Seq[AbsolutePath],
      javaHome: Option[String],
  ): (Seq[AbsolutePath], Seq[AbsolutePath]) = {
    val missingScala: Boolean = {
      val libraryName =
        if (ScalaVersions.isScala3Version(scalaVersion))
          "scala-library"
        else
          "scala3-library"
      sources.filter(_.toString.contains(libraryName)).isEmpty
    }

    (missingScala, JdkSources(javaHome)) match {
      case (true, Left(_)) =>
        val (scalaSources, scalaClasspath) = getScala(scalaVersion)
        (sources ++ scalaSources, classpath ++ scalaClasspath)
      case (true, Right(absPath)) =>
        val (scalaSources, scalaClasspath) = getScala(scalaVersion)
        (
          (scalaSources :+ absPath) ++ sources,
          classpath ++ scalaClasspath,
        )
      case (false, Left(_)) => (sources, classpath)
      case (false, Right(absPath)) =>
        (sources :+ absPath, classpath)
    }
  }

  /**
   * Retrieve scala for the given version and partition sources.
   * @param scalaVersion
   * @return (scalaSources, scalaClasspath)
   */
  private def getScala(
      scalaVersion: String
  ): (Seq[AbsolutePath], Seq[AbsolutePath]) = {
    val download =
      if (ScalaVersions.isScala3Version(scalaVersion))
        Embedded.downloadScala3Sources _
      else
        Embedded.downloadScalaSources _

    download(scalaVersion).toSeq
      .map(path => AbsolutePath(path))
      .partition(_.toString.endsWith("-sources.jar"))
  }

}
