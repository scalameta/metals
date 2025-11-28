package scala.meta.internal.metals

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath
object ScalaVersionSelector {
  def default: ScalaVersionSelector = new ScalaVersionSelector(
    () => UserConfiguration.default,
    BuildTargets.empty,
  )
}

class ScalaVersionSelector(
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets,
) {

  def scalaVersionForPath(path: AbsolutePath): String = {
    buildTargets
      .scalaVersion(path)
      .getOrElse(fallbackScalaVersion(path.isAmmoniteScript))
  }

  def fallbackScalaVersion(isAmmonite: Boolean): String = {
    val selected = userConfig().fallbackScalaVersion match {
      case Some(v) => v
      case None =>
        buildTargets.allScala.toList
          .map(_.scalaInfo.getScalaVersion)
          .sorted
          .lastOption
          .getOrElse(BuildInfo.scala213)
    }

    val binary = ScalaVersions.scalaBinaryVersionFromFullVersion(selected)
    if (isAmmonite && ScalaVersions.isScala3Version(selected))
      BuildInfo.ammonite3
    else if (
      isAmmonite && binary == "2.12" && SemVer.isLaterVersion(
        BuildInfo.ammonite212,
        selected,
      )
    )
      BuildInfo.ammonite212
    else if (
      isAmmonite && binary == "2.13" && SemVer.isLaterVersion(
        BuildInfo.ammonite213,
        selected,
      )
    )
      BuildInfo.ammonite213
    else if (ScalaVersions.isSupportedAtReleaseMomentScalaVersion(selected))
      selected
    else
      ScalaVersions.recommendedVersion(selected)
  }

  def fallbackDialect(isAmmonite: Boolean): Dialect = {
    ScalaVersions.dialectForScalaVersion(
      fallbackScalaVersion(isAmmonite),
      includeSource3 = true,
    )
  }

  def dialectFromBuildTarget(path: AbsolutePath): Option[Dialect] = buildTargets
    .inverseSources(path)
    .flatMap(id => buildTargets.scalaTarget(id))
    .map(_.dialect(path))

  def getDialect(path: AbsolutePath): Dialect = {
    Option(path.extension) match {
      case Some("scala") =>
        dialectFromBuildTarget(path).getOrElse(
          fallbackDialect(isAmmonite = false)
        )
      case Some("sbt") => dialects.Sbt
      case Some("sc") =>
        // worksheets support Scala 3, but ammonite scripts do not
        val dialect = dialectFromBuildTarget(path).getOrElse(
          fallbackDialect(isAmmonite = path.isAmmoniteScript)
        )
        dialect
          .withAllowToplevelTerms(true)
          .withToplevelSeparator("")
      case _ => fallbackDialect(isAmmonite = false)
    }
  }
}
