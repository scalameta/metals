package scala.meta.internal.metals.mbt

import scala.concurrent.Future
import scala.jdk.CollectionConverters.ListHasAsScala

import scala.meta.internal.builds.BuildTool
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuites

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

  def mbtTestCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      sourceFiles: Seq[AbsolutePath],
  ): Future[List[String]]

  def mbtTestDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      debugAgentFlag: String,
      sourceFiles: Seq[AbsolutePath],
  ): Future[List[String]]

  /**
   * Returns true if this launcher supports forked test debugging with a pre-assigned port.
   * When true, mbtTestDebugCommandWithPort should be used instead of mbtTestDebugCommand.
   */
  def supportsForkedTestDebug: Boolean = false

  /**
   * Returns a function that builds the test debug command with a specific port.
   * The forked test JVM will listen on this port for debugger connections.
   */
  def mbtTestDebugCommandWithPort(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      sourceFiles: Seq[AbsolutePath],
  ): Int => Future[List[String]] = { _ =>
    mbtTestDebugCommand(
      workspace,
      target,
      testSuites,
      MbtDebugLauncher.DebugAgentFlag,
      sourceFiles,
    )
  }
}

object MbtDebugLauncher {

  val DebugAgentFlag: String =
    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n"

  def listOrNil[A](l: java.util.List[A]): List[A] =
    if (l == null) Nil else l.asScala.toList
}
