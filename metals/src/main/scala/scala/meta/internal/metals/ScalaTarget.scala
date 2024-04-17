package scala.meta.internal.metals

import scala.meta.Dialect
import scala.meta.dialects._
import scala.meta.internal.builds.BazelBuildTool
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
) extends JvmTarget {

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
          case Scala3
              if scalac
                .getOptions()
                .asScala
                .exists(_.startsWith("-Ykind-projector")) =>
            dialect.withAllowStarAsTypePlaceholder(true)
          case Scala3 =>
            // set to false since this needs an additional compiler option
            Scala3.withAllowStarAsTypePlaceholder(false)
          case other => other
        }
    }
  }

  def displayName: String = info.getName

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
      buildServer.name == BazelBuildTool.bspName ||
      buildServer.name == MillBuildTool.bspName &&
      SemVer.isCompatibleVersion(
        MillBuildTool.scalaSemanticDbSupport,
        buildServer.version,
      )
  }

  def isAmmonite: Boolean = displayName.endsWith(".sc")

  def semanticdbFilesPresent(): Boolean = targetroot
    .resolve(Directories.semanticdb)
    .listRecursive
    .exists(_.isSemanticdb)

  def isSemanticdbEnabled: Boolean =
    scalac.isSemanticdbEnabled(scalaVersion) ||
      semanticDbEnabledAlternatively || isAmmonite

  def isSourcerootDeclared: Boolean =
    scalac.isSourcerootDeclared(scalaVersion) || semanticDbEnabledAlternatively

  /**
   * If the build server supports lazy classpath resolution, we will
   * not get any classpath data eagerly and we should not
   * use this endpoint. It should only be used as a fallback.
   *
   * This is due to the fact that we don't request classpath as it
   * can be resonably expensive.
   *
   * @return non empty classpath only if it was resolved prior
   */
  def classpath: Option[List[String]] =
    if (scalac.getClasspath().isEmpty)
      None
    else
      Some(scalac.getClasspath().asScala.toList)

  def classDirectory: String = scalac.getClassDirectory()

  def scalaVersion: String = scalaInfo.getScalaVersion()

  def id: BuildTargetIdentifier = info.getId()

  def scalaBinaryVersion: String = scalaInfo.getScalaBinaryVersion()

  private def containsSource3 =
    scalac.getOptions().asScala.exists(opt => opt.startsWith("-Xsource:3"))

  def targetroot: AbsolutePath = scalac.targetroot(scalaVersion).resolveIfJar

  def scalaPlatform: ScalaPlatform = scalaInfo.getPlatform()

  private def jvmBuildTarget: Option[JvmBuildTarget] = Option(
    scalaInfo.getJvmBuildTarget()
  )

  def jvmVersion: Option[String] =
    jvmBuildTarget.flatMap(f => Option(f.getJavaVersion()))

  def jvmHome: Option[String] =
    jvmBuildTarget.flatMap(f => Option(f.getJavaHome()))
}
