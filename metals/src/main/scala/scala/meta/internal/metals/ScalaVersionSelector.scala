package scala.meta.internal.metals

import scala.meta.Dialect

class ScalaVersionSelector(
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets
) {

  def fallbackScalaVersion(allowScala3: Boolean): String = {
    val selected = userConfig().fallbackScalaVersion match {
      case Some(v) => v
      case None =>
        buildTargets.all.toList
          .map(_.scalaInfo.getScalaVersion)
          .sorted
          .lastOption
          .getOrElse(BuildInfo.scala212)
    }

    // ammonite doesn't support Scala3 yet
    if (!allowScala3 && ScalaVersions.isScala3Version(selected))
      BuildInfo.scala213
    else if (ScalaVersions.isSupportedScalaVersion(selected))
      selected
    else
      ScalaVersions.recommendedVersion(selected)
  }

  def fallbackDialect(allowScala3: Boolean): Dialect = {
    ScalaVersions.dialectForScalaVersion(fallbackScalaVersion(allowScala3))
  }
}
