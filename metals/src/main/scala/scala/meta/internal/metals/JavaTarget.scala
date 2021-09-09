package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.JavacOptionsItem

case class JavaTarget(
    info: BuildTarget,
    javac: JavacOptionsItem
) {
  def displayName: String = info.getDisplayName()

  def dataKind: String = info.dataKind

  def baseDirectory: String = info.baseDirectory

  def classDirectory: String = javac.getClassDirectory()

  def classpath: List[String] = javac.classpath

  def options: List[String] = javac.getOptions().asScala.toList

  def isSemanticdbEnabled: Boolean = javac.isSemanticdbEnabled

  def isSourcerootDeclared: Boolean = javac.isSourcerootDeclared

  def isTargetrootDeclared: Boolean = javac.isTargetrootDeclared

  def releaseVersion: Option[String] = javac.releaseVersion

  def targetVersion: Option[String] = javac.targetVersion

  def sourceVersion: Option[String] = javac.sourceVersion
}
