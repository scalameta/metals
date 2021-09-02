package scala.meta.internal.metals

import java.nio.file.Path

import scala.meta.Dialect
import scala.meta.dialects._
import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.BuildTarget
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
      case _ if info.isSbtBuild => Sbt
      case other =>
        val dialect =
          ScalaVersions.dialectForScalaVersion(other, includeSource3 = false)
        dialect match {
          case Scala213 if containsSource3 =>
            Scala213Source3
          case Scala212 if containsSource3 =>
            Scala212Source3
          case other => other
        }
    }
  }

  def displayName: String = info.getDisplayName()

  def dataKind: String = info.dataKind

  def baseDirectory: String = info.baseDirectory

  def fmtDialect: ScalafmtDialect =
    ScalaVersions.fmtDialectForScalaVersion(scalaVersion, containsSource3)

  def isSemanticdbEnabled: Boolean = scalac.isSemanticdbEnabled(scalaVersion)

  def isSourcerootDeclared: Boolean = scalac.isSourcerootDeclared(scalaVersion)

  def fullClasspath: List[Path] = scalac.classpath.map(_.toAbsolutePath.toNIO)

  def scalaVersion: String = scalaInfo.getScalaVersion()

  def scalaBinaryVersion: String = scalaInfo.getScalaBinaryVersion()

  private def containsSource3 = scalac.getOptions().contains("-Xsource:3")
}
