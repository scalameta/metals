package tests.mbt

import java.nio.file.Files
import java.util.Collections

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Properties

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.mbt.MbtDebugLauncher
import scala.meta.internal.metals.mbt.MbtDebugSessionStarter
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.internal.metals.mbt.MbtTestCommand
import scala.meta.io.AbsolutePath

import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuites
import tests.BaseSuite

class MbtDebugCancelSuite extends BaseSuite {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private val sleepCommand: List[String] =
    if (Properties.isWin) List("ping", "-n", "30", "127.0.0.1")
    else List("sleep", "30")

  test("cancel-running-test") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-cancel"))
    val starter = new MbtDebugSessionStarter(
      debugConfigCreator = null,
      buildTool = new SleepLauncher(workspace, sleepCommand),
      userJavaHome = () => None,
      workDoneProgress = EmptyWorkDoneProgress,
      buildTargetClasses = null,
      testProvider = null,
    )
    val cancel = Promise[Unit]()
    val running = starter.test(
      target = mbtTarget,
      testSuites = new ScalaTestSuites(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
      ),
      workspace = workspace,
      out = _ => (),
      err = _ => (),
      cancel = cancel,
    )
    Thread.sleep(300)
    cancel.trySuccess(())
    val exitCode = Await.result(running, 10.seconds).exitCode
    assert(exitCode != 0)
  }

  private def mbtTarget: MbtTarget =
    MbtTarget(
      name = "app",
      id = new BuildTargetIdentifier("mbt://namespace/app"),
      sources = Nil,
      globMatchers = Nil,
      scalacOptions = Nil,
      javacOptions = Nil,
      dependencyModules = Nil,
    )

  private class SleepLauncher(workspace: AbsolutePath, command: List[String])
      extends BuildTool
      with MbtDebugLauncher {
    override def executableName: String = "fake"
    override def projectRoot: AbsolutePath = workspace
    protected def digest(workspace: AbsolutePath): Option[String] = None

    override def mbtCompileCommand(
        workspace: AbsolutePath,
        target: MbtTarget,
    ): List[String] = command

    override def mbtRunCommand(
        workspace: AbsolutePath,
        target: MbtTarget,
        mainClass: ScalaMainClass,
    ): List[String] = command

    override def mbtDebugCommand(
        workspace: AbsolutePath,
        target: MbtTarget,
        mainClass: ScalaMainClass,
        debugAgentFlag: String,
    ): List[String] = command

    override def mbtTestCommand(
        workspace: AbsolutePath,
        target: MbtTarget,
        testSuites: ScalaTestSuites,
        sourceFiles: Seq[AbsolutePath],
        framework: Option[TestFramework],
    ): Future[MbtTestCommand] = Future.successful(MbtTestCommand(command))

    override def mbtTestDebugCommand(
        workspace: AbsolutePath,
        target: MbtTarget,
        testSuites: ScalaTestSuites,
        debugAgentFlag: String,
        sourceFiles: Seq[AbsolutePath],
        framework: Option[TestFramework],
    ): Future[MbtTestCommand] = Future.successful(MbtTestCommand(command))
  }
}
