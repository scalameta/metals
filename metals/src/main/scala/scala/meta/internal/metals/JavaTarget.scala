package scala.meta.internal.metals

import java.nio.file.Path

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JavacOptionsItem

case class JavaTarget(
    info: BuildTarget,
    javac: JavacOptionsItem,
    bspConnection: Option[BuildServerConnection],
) {
  def displayName: String = info.getDisplayName()

  def dataKind: String = info.dataKind

  def baseDirectory: String = info.baseDirectory

  def fullClasspath: List[Path] =
    javac.classpath.map(_.toAbsolutePath).collect {
      case path if path.isJar || path.isDirectory =>
        path.toNIO
    }

  def options: List[String] = javac.getOptions().asScala.toList

  def isSemanticdbEnabled: Boolean =
    javac.isSemanticdbEnabled || semanticDbEnabledAlternatively

  def isSourcerootDeclared: Boolean =
    javac.isSourcerootDeclared || semanticDbEnabledAlternatively

  def isTargetrootDeclared: Boolean =
    javac.isTargetrootDeclared || semanticDbEnabledAlternatively

  def classDirectory: String = javac.getClassDirectory()

  def id: BuildTargetIdentifier = info.getId()

  def releaseVersion: Option[String] = javac.releaseVersion

  def targetVersion: Option[String] = javac.targetVersion

  def sourceVersion: Option[String] = javac.sourceVersion

  def targetroot: AbsolutePath = javac.targetroot

  /**
   * Typically to verify that SemanticDB is enabled correctly we check the javacOptions to ensure
   * that both we see that it's enabled and that things like the sourceroot are set correctly.
   * There are servers that configure SemanticDB in a non-traditional way. For those situations
   * our check isn't as robust, but for the initial check here we just mark them as OK since
   * we know and trust that for that given version and build server it should be configured.
   *
   * This is the case for mill-bsp >= 0.11.0-M2 for Java targets
   */
  private def semanticDbEnabledAlternatively = bspConnection.exists {
    buildServer =>
      buildServer.name == MillBuildTool.bspName &&
      SemVer.isCompatibleVersion(
        MillBuildTool.javaSemanticDbSupport,
        buildServer.version,
      )
  }
}
