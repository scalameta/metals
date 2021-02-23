package scala.meta.internal.metals

import scala.meta.Dialect
import scala.meta.internal.semver.SemVer

class ScalaVersionSelector(
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets
) {

  def fallbackScalaVersion(isAmmonite: Boolean): String = {
    val selected = userConfig().fallbackScalaVersion match {
      case Some(v) => v
      case None =>
        buildTargets.all.toList
          .map(_.scalaInfo.getScalaVersion)
          .sorted
          .lastOption
          .getOrElse(BuildInfo.scala212)
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
    else if (ScalaVersions.isSupportedScalaVersion(selected))
      selected
    else
      ScalaVersions.recommendedVersion(selected)
  }

  def fallbackDialect(isAmmonite: Boolean): Dialect = {
    ScalaVersions.dialectForScalaVersion(fallbackScalaVersion(isAmmonite))
  }
}
