package scala.meta.internal.metals.mbt

import scala.concurrent.Future
import scala.jdk.CollectionConverters.ListHasAsScala

import scala.meta.internal.builds.BuildTool
import scala.meta.io.AbsolutePath

import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuiteSelection
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
      framework: Option[TestFramework] = None,
  ): Future[List[String]]

  def mbtTestDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      debugAgentFlag: String,
      sourceFiles: Seq[AbsolutePath],
      framework: Option[TestFramework] = None,
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
      framework: Option[TestFramework] = None,
  ): Int => Future[List[String]] = { _ =>
    mbtTestDebugCommand(
      workspace,
      target,
      testSuites,
      MbtDebugLauncher.DebugAgentFlag,
      sourceFiles,
      framework,
    )
  }
}

object MbtDebugLauncher {

  val DebugAgentFlag: String =
    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n"

  def listOrNil[A](l: java.util.List[A]): List[A] =
    if (l == null) Nil else l.asScala.toList

  /**
   * Test case names of a suite, ready to be put into a build tool's test filter.
   *
   * JUnit 5 cases are discovered as `method()`, because that is how
   * jupiter-interface reports them and how the client identifies them. Build
   * tool filters (bazel `--test_filter`, gradle `--tests`, maven `-Dtest`) all
   * match the bare method name, so the parentheses have to be dropped. Only for
   * JUnit, since test names of the other frameworks are free-form strings that
   * may legitimately end with `()`.
   */
  def testFilterNames(
      suite: ScalaTestSuiteSelection,
      framework: Option[TestFramework],
  ): List[String] = {
    val tests = listOrNil(suite.getTests)
    if (framework.contains(TestFramework.JUnit)) tests.map(_.stripSuffix("()"))
    else tests
  }
}
