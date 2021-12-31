package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

case class CommonTarget(val info: BuildTarget) {

  def id: BuildTargetIdentifier = info.getId()

  def dataKind: String = info.dataKind

  def baseDirectory: String = info.baseDirectory

  def displayName: String = info.getDisplayName()
}
