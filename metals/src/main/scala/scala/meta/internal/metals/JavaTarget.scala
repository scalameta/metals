package scala.meta.internal.metals

import java.{util => ju}

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JavacOptionsItem

case class JavaTarget(
    info: BuildTarget,
    javac: JavacOptionsItem
) extends CommonTarget {

  override def id: BuildTargetIdentifier = info.getId()

  // TODO - is this needed for javac
  //def targetroot: AbsolutePath = javac.targetroot(scalaVersion)

  override def targetBaseDirectory: String = info.getBaseDirectory()

  override def optionsClasspath: ju.List[String] = javac.getClasspath()

  override def classDirectory: String = javac.getClassDirectory()

  override def displayName: String = info.getDisplayName()
}
