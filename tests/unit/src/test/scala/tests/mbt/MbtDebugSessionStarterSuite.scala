package tests.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.mbt.MbtDebugLauncher
import scala.meta.internal.metals.mbt.MbtDebugSessionStarter
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.io.AbsolutePath

import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuites
import tests.BaseSuite

class MbtDebugSessionStarterSuite extends BaseSuite {

  test("run-command-and-input") {
    assume(!isWindows)
    implicit val ec: ExecutionContext = ExecutionContext.global
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-terminal"))
    val command = List(
      "/bin/sh",
      "-c",
      "read value; printf 'received:%s\\n' \"$value\"",
    )
    val launcher = new TestBuildTool(workspace, command)
    val starter = new MbtDebugSessionStarter(
      debugConfigCreator = null,
      buildTool = launcher,
      userJavaHome = () => None,
      workDoneProgress = EmptyWorkDoneProgress,
      buildTargetClasses = null,
      testProvider = null,
    )
    val target = MbtTarget(
      name = "app",
      id = new BuildTargetIdentifier("mbt://app"),
      sources = Nil,
      globMatchers = Nil,
      scalacOptions = Nil,
      javacOptions = Nil,
      dependencyModules = Nil,
    )
    val output = new ConcurrentLinkedQueue[String]()
    val mainClass = new ScalaMainClass(
      "example.Main",
      Nil.asJava,
      Nil.asJava,
    )

    val exitCode = Await.result(
      starter.run(
        target,
        mainClass,
        workspace,
        output.add,
        output.add,
        process => {
          process.outputStream.write("hello\n".getBytes(StandardCharsets.UTF_8))
          process.outputStream.flush()
        },
      ),
      10.seconds,
    )

    assertEquals(exitCode, 0)
    assertEquals(
      output.asScala.toSeq,
      Seq(s"> ${command.mkString(" ")}", "received:hello"),
    )
  }

  private class TestBuildTool(
      override val projectRoot: AbsolutePath,
      command: List[String],
  ) extends BuildTool
      with MbtDebugLauncher {
    override protected def digest(workspace: AbsolutePath): Option[String] =
      None

    override def executableName: String = "test"

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
    ): Future[List[String]] = Future.successful(command)

    override def mbtTestDebugCommand(
        workspace: AbsolutePath,
        target: MbtTarget,
        testSuites: ScalaTestSuites,
        debugAgentFlag: String,
        sourceFiles: Seq[AbsolutePath],
        framework: Option[TestFramework],
    ): Future[List[String]] = Future.successful(command)
  }
}
