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
    trees: Trees,
    buildTargets: BuildTargets,
    workspaceFallback: Option[SymbolSearch] = None
) extends SymbolSearch {

  private val dependencySourceCache =
    new TrieMap[AbsolutePath, ju.List[String]]()
  private val classpathSearch =
    ClasspathSearch.fromClasspath(
      classpath.toList.map(_.toNIO),
      isExcludedPackage
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
      buildTargets
    )

  def documentation(symbol: String): ju.Optional[SymbolDocumentation] =
    docs
      .documentation(symbol)
      .asScala
      .orElse(workspaceFallback.flatMap(_.documentation(symbol).asScala))
      .asJava

  override def definition(x: String, source: URI): ju.List[Location] = {
    val sourcePath = Option(source).map(AbsolutePath.fromAbsoluteUri)
    destinationProvider
      .fromSymbol(x, sourcePath)
      .flatMap(_.toResult)
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
          mtags.toplevels(input).asJava
        )
      }
      .orElse(workspaceFallback.map(_.definitionSourceToplevels(sym, source)))
      .getOrElse(ju.Collections.emptyList())
  }

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
      scalaVersion: String,
      workspace: AbsolutePath,
      buffers: Buffers,
      sources: Seq[Path],
      classpath: Seq[Path],
      isExcludedPackage: String => Boolean,
      userConfig: () => UserConfiguration,
      trees: Trees,
      buildTargets: BuildTargets
  ): StandaloneSymbolSearch = {
    val (sourcesWithExtras, classpathWithExtras) =
      addScalaAndJava(
        scalaVersion,
        sources.map(AbsolutePath(_)),
        classpath.map(AbsolutePath(_)),
        userConfig().javaHome
      )

    new StandaloneSymbolSearch(
      workspace,
      classpathWithExtras,
      sourcesWithExtras,
      buffers,
      isExcludedPackage,
      trees,
      buildTargets
    )
  }
  def apply(
      scalaVersion: String,
      workspace: AbsolutePath,
      buffers: Buffers,
      isExcludedPackage: String => Boolean,
      userConfig: () => UserConfiguration,
      trees: Trees,
      buildTargets: BuildTargets
  ): StandaloneSymbolSearch = {
    val (sourcesWithExtras, classpathWithExtras) =
      addScalaAndJava(scalaVersion, Nil, Nil, userConfig().javaHome)

    new StandaloneSymbolSearch(
      workspace,
      classpathWithExtras,
      sourcesWithExtras,
      buffers,
      isExcludedPackage,
      trees,
      buildTargets
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
      javaHome: Option[String]
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
      case (true, None) =>
        val (scalaSources, scalaClasspath) = getScala(scalaVersion)
        (sources ++ scalaSources, classpath ++ scalaClasspath)
      case (true, Some(absPath)) =>
        val (scalaSources, scalaClasspath) = getScala(scalaVersion)
        (
          (scalaSources :+ absPath) ++ sources,
          classpath ++ scalaClasspath
        )
      case (false, None) => (sources, classpath)
      case (false, Some(absPath)) =>
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
