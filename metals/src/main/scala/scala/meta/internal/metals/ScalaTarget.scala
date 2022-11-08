package scala.meta.internal.metals

import java.nio.file.Path

import scala.meta.Dialect
import scala.meta.dialects._
import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JvmBuildTarget
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalaPlatform
import ch.epfl.scala.bsp4j.ScalacOptionsItem

case class ScalaTarget(
    info: BuildTarget,
    scalaInfo: ScalaBuildTarget,
    scalac: ScalacOptionsItem,
    autoImports: Option[Seq[String]],
    sbtVersion: Option[String],
    bspConnection: Option[BuildServerConnection],
) {

  def isSbt = sbtVersion.isDefined

  def dialect(path: AbsolutePath): Dialect = {
    scalaVersion match {
      case _ if info.isSbtBuild && path.isSbt => Sbt
      case other =>
        val dialect =
          ScalaVersions.dialectForScalaVersion(other, includeSource3 = false)
        dialect match {
          case Scala213 if containsSource3 =>
            Scala213Source3
          case Scala212 if containsSource3 =>
            Scala212Source3
          case Scala3 if other.contains("NIGHTLY") =>
            Scala3.withAllowFewerBraces(true)
          case other => other
        }
    }
  }

  def displayName: String = info.getDisplayName()

  def dataKind: String = info.dataKind

  def baseDirectory: String = info.baseDirectory

  def options: List[String] = scalac.getOptions().asScala.toList

  def fmtDialect: ScalafmtDialect =
    ScalaVersions.fmtDialectForScalaVersion(scalaVersion, containsSource3)

  /**
   * Typically to verify that SemanticDB is enabled correctly we check the scalacOptions to ensure
   * that both we see that it's enabled and that things like the sourceroot are set correctly.
   * There are server that configure SemanticDB in a non-traditional way. For those situations
   * our check isn't as robust, but for the initial check here we just mark them as OK since
   * we know and trust that for that given version and build server it should be configured.
   *
   * This is the case for mill-bsp >= 0.10.6
   */
  private def semanticDbEnabledAlternatively = bspConnection.exists {
    buildServer =>
      buildServer.name == MillBuildTool.name &&
      SemVer.isCompatibleVersion(
        MillBuildTool.emitsSemanticDbByDefault,
        buildServer.version,
      )
  }

  def isAmmonite: Boolean = displayName.endsWith(".sc")

  def isSemanticdbEnabled: Boolean =
    scalac.isSemanticdbEnabled(scalaVersion) ||
      semanticDbEnabledAlternatively || isAmmonite

  def isSourcerootDeclared: Boolean =
    scalac.isSourcerootDeclared(scalaVersion) || semanticDbEnabledAlternatively

  def fullClasspath: List[Path] =
    scalac.classpath.map(_.toAbsolutePath).collect {
      case path if path.isJar || path.isDirectory =>
        path.toNIO
    }

  def classDirectory: String = scalac.getClassDirectory()

  def scalaVersion: String = scalaInfo.getScalaVersion()

  def id: BuildTargetIdentifier = info.getId()

  def scalaBinaryVersion: String = scalaInfo.getScalaBinaryVersion()

  private def containsSource3 =
    scalac.getOptions().asScala.exists(opt => opt.startsWith("-Xsource:3"))

  def targetroot: AbsolutePath = scalac.targetroot(scalaVersion)

  def scalaPlatform: ScalaPlatform = scalaInfo.getPlatform()

  private def jvmBuildTarget: Option[JvmBuildTarget] = Option(
    scalaInfo.getJvmBuildTarget()
  )

  def jvmVersion: Option[String] =
    jvmBuildTarget.flatMap(f => Option(f.getJavaVersion()))

  def jvmHome: Option[String] =
    jvmBuildTarget.flatMap(f => Option(f.getJavaHome()))
}
