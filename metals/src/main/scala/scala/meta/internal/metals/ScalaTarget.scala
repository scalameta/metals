package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import java.{util => ju}
import java.nio.file.Path
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

case class ScalaTarget(
    info: BuildTarget,
    scalaInfo: ScalaBuildTarget,
    scalac: ScalacOptionsItem
) {
  def isSemanticdbEnabled: Boolean = scalac.isSemanticdbEnabled

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

  def displayName: String = info.getDisplayName()

  def scalaBinaryVersion: String = scalaInfo.getScalaVersion()
}
