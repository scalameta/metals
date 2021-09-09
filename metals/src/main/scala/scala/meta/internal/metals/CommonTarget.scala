package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.BuildTargetCapabilities

case class CommonTarget(val info: BuildTarget) {

  def id: BuildTargetIdentifier = info.getId()

  def dataKind: String = info.dataKind

  def baseDirectory: String = info.baseDirectory

  def displayName: String = info.getDisplayName()

  def tags: List[String] = info.getTags().asScala.toList

  def languageIds: List[String] = info.getLanguageIds().asScala.toList

  def dependencies: List[BuildTargetIdentifier] =
    info.getDependencies().asScala.toList

  def capabilities: BuildTargetCapabilities = info.getCapabilities()

  def data = info.getData()
}
