package scala.meta.internal.metals

import java.{util => ju}

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JavacOptionsItem

case class JavaTarget(
    info: BuildTarget,
    javac: JavacOptionsItem
) extends CommonTarget {

  override def isSemanticdbEnabled: Boolean = javac.isSemanticdbEnabled

  override def isSourcerootDeclared: Boolean = javac.isSourcerootDeclared

  override def id: BuildTargetIdentifier = info.getId()

  override def targetroot: AbsolutePath = javac.targetroot

  override def targetBaseDirectory: String = info.getBaseDirectory()

  override def optionsClasspath: ju.List[String] = javac.getClasspath()

  override def classDirectory: String = javac.getClassDirectory()

  override def displayName: String = info.getDisplayName()
}
