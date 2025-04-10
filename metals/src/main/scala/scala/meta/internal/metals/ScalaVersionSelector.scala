package scala.meta.internal.metals

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

class ScalaVersionSelector(
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets,
) {

  def scalaVersionForPath(path: AbsolutePath): String = {
    buildTargets
      .scalaVersion(path)
      .getOrElse(fallbackScalaVersion())
  }

  def fallbackScalaVersion(): String = {
    val selected = userConfig().fallbackScalaVersion match {
      case Some(v) => v
      case None =>
        buildTargets.allScala.toList
          .map(_.scalaInfo.getScalaVersion)
          .sorted
          .lastOption
          .getOrElse(BuildInfo.scala3)
    }

    if (ScalaVersions.isSupportedAtReleaseMomentScalaVersion(selected))
      selected
    else
      ScalaVersions.recommendedVersion(selected)
  }

  def fallbackDialect(): Dialect = {
    ScalaVersions.dialectForScalaVersion(
      fallbackScalaVersion(),
      includeSource3 = true,
    )
  }

  def dialectFromBuildTarget(path: AbsolutePath): Option[Dialect] = buildTargets
    .inverseSources(path)
    .flatMap(id => buildTargets.scalaTarget(id))
    .map(_.dialect(path))

  def getDialect(path: AbsolutePath): Dialect = {
    Option(path.extension) match {
      case _ if path.isMill =>
        dialectFromBuildTarget(path)
          .getOrElse(fallbackDialect())
          .withAllowToplevelTerms(true)
          .withAllowToplevelStatements(true)
      case Some("scala") =>
        dialectFromBuildTarget(path).getOrElse(
          fallbackDialect()
        )
      case Some("sbt") => dialects.Sbt
      case Some("sc") =>
        val dialect = dialectFromBuildTarget(path).getOrElse(
          fallbackDialect()
        )
        dialect
          .withAllowToplevelTerms(true)
          .withToplevelSeparator("")
      case _ => fallbackDialect()
    }
  }
}
