package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import scala.meta.internal.metals.MetalsEnrichments._

case class ScalaTarget(info: BuildTarget, scalac: ScalacOptionsItem) {
  def isSemanticdbEnabled: Boolean = {
    scalac.getOptions.asScala.exists { opt =>
      opt.startsWith("-Xplugin:") && opt
        .contains("semanticdb-scalac")
    }
  }
}
