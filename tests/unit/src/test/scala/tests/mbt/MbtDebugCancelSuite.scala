package tests.mbt

import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Properties

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.server.DebugeeParamsCreator
import scala.meta.internal.metals.debug.server.DebugeeProject
import scala.meta.internal.metals.mbt.MbtDebugLauncher
import scala.meta.internal.metals.mbt.MbtDebugSessionStarter
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.internal.metals.mbt.MbtTestCommand
import scala.meta.io.AbsolutePath

import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JvmEnvironmentItem
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

  for {
    isTest <- List(false, true)
    cancelCompile <- List(false, true)
  } {
    val outcome = if (cancelCompile) "cancellation" else "failure"
    test(s"compile-$outcome-prevents-debug-${if (isTest) "test" else "main"}") {
      val workspace =
        AbsolutePath(Files.createTempDirectory("mbt-debug-failure"))
      val command =
        if (cancelCompile) sleepCommand
        else if (Properties.isWin) List("cmd", "/c", "exit", "7")
        else List("sh", "-c", "exit 7")
      val starter = new MbtDebugSessionStarter(
        debugConfigCreator = null,
        buildTool = new SleepLauncher(workspace, command),
        userJavaHome = () => None,
        workDoneProgress = EmptyWorkDoneProgress,
        buildTargetClasses = null,
        testProvider = null,
      )
      val cancel = Promise[Unit]()
      val running = startDebug(starter, workspace, cancel, isTest)
      if (cancelCompile) {
        Thread.sleep(300)
        cancel.trySuccess(())
        intercept[CancellationException] {
          Await.result(running, 10.seconds)
        }
      } else {
        val error = intercept[IllegalStateException] {
          Await.result(running, 10.seconds)
        }
        assertEquals(
          error.getMessage,
          "MBT compile failed for app (exit code 7)",
        )
      }
    }
  }

  for (isTest <- List(false, true)) {
    test(s"adapter-start-timeout-debug-${if (isTest) "test" else "main"}") {
      val callbacks =
        ExecutionContext.fromExecutorService(
          Executors.newSingleThreadExecutor()
        )
      try {
        val workspace =
          AbsolutePath(Files.createTempDirectory("mbt-debug-timeout"))
        val classes =
          new BuildTargetClasses(BuildTargets.empty, () => null, null)(
            callbacks
          )
        val project = Promise[DebugeeProject]()
        val adapterCreationAttempts = new AtomicInteger(0)
        val cancel = Promise[Unit]()
        val creator = new DebugeeParamsCreator(classes) {
          override def create(
              id: BuildTargetIdentifier,
              cancelPromise: Promise[Unit],
              isTests: Boolean,
              jvmRunEnvironmentFuture: Option[
                Future[Option[JvmEnvironmentItem]]
              ],
          )(implicit
              executionContext: ExecutionContext
          ): Either[String, Future[DebugeeProject]] =
            Right(project.future)
        }
        val command =
          if (Properties.isWin) List("cmd", "/c", "exit", "0")
          else List("sh", "-c", "exit 0")
        val starter = new MbtDebugSessionStarter(
          debugConfigCreator = creator,
          buildTool = new SleepLauncher(workspace, command),
          userJavaHome = () => {
            if (cancel.isCompleted) {
              adapterCreationAttempts.incrementAndGet()
              throw new IllegalStateException(
                "Creating an adapter after cancellation"
              )
            }
            None
          },
          workDoneProgress = EmptyWorkDoneProgress,
          buildTargetClasses = null,
          testProvider = null,
          debugServerStartTimeoutSeconds = 1,
        )(callbacks)
        intercept[TimeoutException] {
          Await.result(
            startDebug(starter, workspace, cancel, isTest),
            10.seconds,
          )
        }
        assert(cancel.isCompleted)
        project.success(
          DebugeeProject(
            scalaVersion = None,
            name = "app",
            modules = Nil,
            libraries = Nil,
            unmanagedEntries = Nil,
            runClassPath = Nil,
            environmentVariables = Map.empty,
            jvmOptions = Nil,
          )
        )
        Await.result(Future(())(callbacks), 10.seconds)
        assertEquals(adapterCreationAttempts.get(), 0)
      } finally {
        callbacks.shutdownNow()
      }
    }
  }

  private def startDebug(
      starter: MbtDebugSessionStarter,
      workspace: AbsolutePath,
      cancel: Promise[Unit],
      isTest: Boolean,
  ): Future[URI] =
    if (isTest)
      starter.startDebugTest(
        mbtTarget,
        new ScalaTestSuites(
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
        ),
        workspace,
        cancel,
      )
    else
      starter.start(
        mbtTarget,
        new ScalaMainClass(
          "example.Main",
          Collections.emptyList(),
          Collections.emptyList(),
        ),
        workspace,
        cancel,
      )

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
