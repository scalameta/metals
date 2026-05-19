package scala.meta.internal.metals.mbt

import java.nio.file.Path
import java.nio.file.Paths
import java.{util => ju}

import scala.util.Properties

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j

case class MbtTarget(
    name: String,
    id: bsp4j.BuildTargetIdentifier,
    sources: Seq[String],
    globMatchers: Seq[MbtGlobMatcher],
    scalacOptions: Seq[String],
    javacOptions: Seq[String],
    dependencyModules: Seq[MbtDependencyModule],
    scalaVersion: Option[String] = None,
    javaHome: Option[String] = None,
    dependsOn: Seq[bsp4j.BuildTargetIdentifier] = Nil,
) {

  // mbt doesn't produce any classfiles
  private def emptyClassDirectory(workspace: AbsolutePath): AbsolutePath = {
    workspace.resolve(".metals/mbt-out").createDirectories()
  }

  private def classpath: ju.List[String] =
    dependencyModules.flatMap(_.jarUri.map(_.toString)).asJava

  private def baseDirectory(workspace: AbsolutePath): AbsolutePath =
    workspace

  def stableSourcePaths(workspace: AbsolutePath): Seq[AbsolutePath] =
    sources.distinct.map(workspace.resolve)

  def containsStableSource(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean =
    stableSourcePaths(workspace).exists(path.startWith)

  def containsSource(workspace: AbsolutePath, path: AbsolutePath): Boolean = {
    containsStableSource(workspace, path) ||
    path.toRelativeInside(workspace).exists { relative =>
      globMatchers.exists(_.matcher.matches(relative.toNIO))
    }
  }

  def shouldScanGlobDirectory(relativeDirectory: Path): Boolean =
    globMatchers.exists(_.mayContainMatchesIn(relativeDirectory))

  private def scalaBinaryVersion(defaultScalaVersion: String): String =
    ScalaVersions.scalaBinaryVersionFromFullVersion(
      scalaVersion.getOrElse(defaultScalaVersion)
    )

  def buildTarget(
      workspace: AbsolutePath,
      scalaVersionSelector: ScalaVersionSelector,
  ): bsp4j.BuildTarget = {
    val capabilities = new bsp4j.BuildTargetCapabilities
    capabilities.setCanCompile(false)
    capabilities.setCanDebug(false)
    capabilities.setCanRun(false)
    capabilities.setCanTest(false)

    val scalaVersion = this.scalaVersion.getOrElse(
      scalaVersionSelector.fallbackScalaVersion()
    )
    val scalaTarget = new bsp4j.ScalaBuildTarget(
      "org.scala-lang",
      scalaVersion,
      scalaBinaryVersion(scalaVersion),
      bsp4j.ScalaPlatform.JVM,
      ju.Collections.emptyList(),
    )
    val jvmBt = new bsp4j.JvmBuildTarget()
    val javaHomeUri = javaHome
      .map {
        case home if home.startsWith("file://") => home
        case home => Paths.get(home).toUri().toString()
      }
      .getOrElse(Paths.get(Properties.javaHome).toUri().toString())
    jvmBt.setJavaHome(javaHomeUri)
    scalaTarget.setJvmBuildTarget(jvmBt)

    val target = new bsp4j.BuildTarget(
      id,
      ju.Collections.emptyList(),
      List("scala", "java").asJava,
      dependsOn.asJava,
      capabilities,
    )
    target.setDisplayName(name)
    target.setBaseDirectory(baseDirectory(workspace).toURI.toString)
    target.setDataKind("scala")
    target.setData(MbtTarget.toGson(scalaTarget))
    target
  }

  def scalacOptionsItem(workspace: AbsolutePath): bsp4j.ScalacOptionsItem =
    new bsp4j.ScalacOptionsItem(
      id,
      scalacOptions.asJava,
      classpath,
      emptyClassDirectory(workspace).toURI.toString(),
    )

  def javacOptionsItem(workspace: AbsolutePath): bsp4j.JavacOptionsItem =
    new bsp4j.JavacOptionsItem(
      id,
      javacOptions.asJava,
      classpath,
      emptyClassDirectory(workspace).toURI.toString(),
    )

  def sourcesItem(
      workspace: AbsolutePath,
      globbedSources: Seq[AbsolutePath] = Nil,
  ): bsp4j.SourcesItem =
    new bsp4j.SourcesItem(
      id,
      (stableSourcePaths(workspace) ++ globbedSources).distinct.map { path =>
        new bsp4j.SourceItem(
          path.toURI.toString,
          if (path.isFile) bsp4j.SourceItemKind.FILE
          else bsp4j.SourceItemKind.DIRECTORY,
          false,
        )
      }.asJava,
    )

  def dependencySourcesItem: bsp4j.DependencySourcesItem =
    new bsp4j.DependencySourcesItem(
      id,
      dependencyModules.flatMap(_.sourcesURI.map(_.toString)).distinct.asJava,
    )

  def dependencyModulesItem: bsp4j.DependencyModulesItem =
    new bsp4j.DependencyModulesItem(
      id,
      dependencyModules.map(_.asBsp).asJava,
    )

}

object MbtTarget {
  private val gson = new com.google.gson.Gson()
  private def toGson(value: bsp4j.ScalaBuildTarget) =
    gson.toJsonTree(value)
}
