package scala.meta.internal.metals.mbt

import scala.jdk.CollectionConverters.ListHasAsScala

import scala.meta.internal.builds.BuildTool
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass

trait MbtDebugLauncher { self: BuildTool =>

  def executableName: String = self.executableName

  def mbtCompileCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
  ): List[String]

  def mbtRunCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
  ): List[String]

  def mbtDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: String,
  ): List[String]
}

object MbtDebugLauncher {

  val DebugAgentFlag: String =
    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n"

  def listOrNil[A](l: java.util.List[A]): List[A] =
    if (l == null) Nil else l.asScala.toList
}
