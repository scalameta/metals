package scala.meta.internal.metals

import java.nio.file.Path
import java.{util => ju}

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

trait CommonTarget {

  def id: BuildTargetIdentifier

  def targetroot: AbsolutePath

  def isSemanticdbEnabled: Boolean

  def isSourcerootDeclared: Boolean

  def targetBaseDirectory: String

  def optionsClasspath: ju.List[String]

  final def baseDirectory: String = {
    val baseDir = targetBaseDirectory
    if (baseDir != null) baseDir else ""
  }

  final def fullClasspath: ju.List[Path] = {
    optionsClasspath.map(_.toAbsolutePath.toNIO)
  }

  final def jarClasspath: List[AbsolutePath] = {
    optionsClasspath.asScala.toList
      .filter(_.endsWith(".jar"))
      .map(_.toAbsolutePath)
  }

  def classDirectory: String

  def displayName: String
}
