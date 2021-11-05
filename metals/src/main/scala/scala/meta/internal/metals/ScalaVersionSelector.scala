package scala.meta.internal.metals

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

class ScalaVersionSelector(
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets
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
        buildTargets.all.toList
          .map(_.scalaInfo.getScalaVersion)
          .sorted
          .lastOption
          .getOrElse(BuildInfo.scala3)
    }

    val binary = ScalaVersions.scalaBinaryVersionFromFullVersion(selected)
    // ammonite doesn't support Scala3 yet
    if (isAmmonite && ScalaVersions.isScala3Version(selected))
      BuildInfo.scala213
    else if (
      isAmmonite && binary == "2.12" && SemVer.isLaterVersion(
        BuildInfo.ammonite212,
        selected
      )
    )
      BuildInfo.ammonite212
    else if (
      isAmmonite && binary == "2.13" && SemVer.isLaterVersion(
        BuildInfo.ammonite213,
        selected
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
      includeSource3 = true
    )
  }

  def getDialect(path: AbsolutePath): Dialect = {

    def dialectFromBuildTarget = buildTargets
      .inverseSources(path)
      .flatMap(id => buildTargets.scalaTarget(id))
      .map(_.dialect(path))

    Option(path.extension) match {
      case Some("scala") =>
        dialectFromBuildTarget.getOrElse(
          fallbackDialect(isAmmonite = false)
        )
      case Some("sbt") => dialects.Sbt
      case Some("sc") =>
        // worksheets support Scala 3, but ammonite scripts do not
        val dialect = dialectFromBuildTarget.getOrElse(
          fallbackDialect(isAmmonite = path.isAmmoniteScript)
        )
        dialect
          .copy(allowToplevelTerms = true, toplevelSeparator = "")
      case _ => fallbackDialect(isAmmonite = false)
    }
  }
}
