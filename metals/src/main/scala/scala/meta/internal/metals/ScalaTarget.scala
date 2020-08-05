package scala.meta.internal.metals

import java.nio.file.Path
import java.{util => ju}

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
    autoImports: Option[Seq[String]]
) {

  def isSemanticdbEnabled: Boolean = scalac.isSemanticdbEnabled(scalaVersion)

  def isSourcerootDeclared: Boolean = scalac.isSourcerootDeclared(scalaVersion)

  def id: BuildTargetIdentifier = info.getId()

  def baseDirectory: String = info.getBaseDirectory()

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
