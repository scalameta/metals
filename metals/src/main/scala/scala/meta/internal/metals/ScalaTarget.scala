package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import java.{util => ju}
import java.nio.file.Path

case class ScalaTarget(info: BuildTarget, scalac: ScalacOptionsItem) {
  def isSemanticdbEnabled: Boolean = scalac.isSemanticdbEnabled

  def isScalaTarget: Boolean = info.getLanguageIds().contains("scala")

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

}
