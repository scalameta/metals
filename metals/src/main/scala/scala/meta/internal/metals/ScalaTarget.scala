package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

case class ScalaTarget(info: BuildTarget, scalac: ScalacOptionsItem) {
  def isSemanticdbEnabled: Boolean = scalac.isSemanticdbEnabled

  def isScalaTarget: Boolean = info.getLanguageIds().contains("scala")

  def fullClasspath: List[AbsolutePath] = {
    scalac
      .getClasspath()
      .asScala
      .toList
      .map(_.toAbsolutePath)
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
