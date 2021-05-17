package scala.meta.internal.metals

import java.nio.file.Path
import java.{util => ju}

import scala.meta.Dialect
import scala.meta.dialects._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem

case class ScalaTarget(
    info: BuildTarget,
    scalaInfo: ScalaBuildTarget,
    scalac: ScalacOptionsItem,
    autoImports: Option[Seq[String]],
    isSbt: Boolean
) {

  def dialect: Dialect = {
    scalaVersion match {
      case _ if info.getDataKind() == "sbt" => Sbt
      case other =>
        val dialect =
          ScalaVersions.dialectForScalaVersion(other, includeSource3 = false)
        def containsSource3 = scalac.getOptions().contains("-Xsource:3")
        dialect match {
          case Scala213 if containsSource3 =>
            Scala213Source3
          case Scala212 if containsSource3 =>
            Scala212Source3
          case other => other
        }
    }
  }

  def isSemanticdbEnabled: Boolean = scalac.isSemanticdbEnabled(scalaVersion)

  def isSourcerootDeclared: Boolean = scalac.isSourcerootDeclared(scalaVersion)

  def id: BuildTargetIdentifier = info.getId()

  def targetroot: AbsolutePath = scalac.targetroot(scalaVersion)

  def baseDirectory: String = {
    val baseDir = info.getBaseDirectory()
    if (baseDir != null) baseDir else ""
  }

  def fullClasspath: ju.List[Path] = {
    scalac.getClasspath().map(_.toAbsolutePath.toNIO)
  }

  def jarClasspath: List[AbsolutePath] = {
    scalac
      .getClasspath()
      .asScala
      .toList
      .filter(_.endsWith(".jar"))
      .map(_.toAbsolutePath)
  }

  def scalaVersion: String = scalaInfo.getScalaVersion()

  def classDirectory: String = scalac.getClassDirectory()

  def displayName: String = info.getDisplayName()

  def scalaBinaryVersion: String = scalaInfo.getScalaBinaryVersion()
}
