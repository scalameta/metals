package scala.meta.internal.metals

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
) extends CommonTarget {

  def dialect: Dialect = {
    scalaVersion match {
      case _ if info.getDataKind() == "sbt" => Sbt
      case other => ScalaVersions.dialectForScalaVersion(other)
    }
  }

  def isSemanticdbEnabled: Boolean = scalac.isSemanticdbEnabled(scalaVersion)

  def isSourcerootDeclared: Boolean = scalac.isSourcerootDeclared(scalaVersion)

  override def id: BuildTargetIdentifier = info.getId()

  def targetroot: AbsolutePath = scalac.targetroot(scalaVersion)

  override def targetBaseDirectory: String = info.getBaseDirectory()

  override def optionsClasspath: ju.List[String] = scalac.getClasspath()

  def scalaVersion: String = scalaInfo.getScalaVersion()

  override def classDirectory: String = scalac.getClassDirectory()

  override def displayName: String = info.getDisplayName()

  def scalaBinaryVersion: String = scalaInfo.getScalaBinaryVersion()
}
