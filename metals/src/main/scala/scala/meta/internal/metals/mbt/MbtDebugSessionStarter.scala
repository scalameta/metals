package scala.meta.internal.metals.mbt

import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.BaseWorkDoneProgress
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.server.BuildToolDebugAdapter
import scala.meta.internal.metals.debug.server.DebugLogger
import scala.meta.internal.metals.debug.server.DebugeeParamsCreator
import scala.meta.internal.metals.debug.server.DebugeeProject
import scala.meta.internal.metals.debug.server.ForkedTestDebugAdapter
import scala.meta.internal.metals.debug.server.MetalsDebugToolsResolver
import scala.meta.internal.metals.testProvider.TestSuitesProvider
import scala.meta.internal.process.ProcessOutput
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuites
import ch.epfl.scala.debugadapter.MultiOutputModule
import ch.epfl.scala.{debugadapter => dap}

class MbtDebugSessionStarter(
    debugConfigCreator: DebugeeParamsCreator,
    buildTool: MbtDebugLauncher,
    userJavaHome: () => Option[String],
    workDoneProgress: BaseWorkDoneProgress,
    buildTargetClasses: BuildTargetClasses,
    testProvider: TestSuitesProvider,
    debuggeeGracePeriodSeconds: Long = 60L,
)(implicit ec: ExecutionContext) {

  def start(
      target: MbtTarget,
      mainClass: ScalaMainClass,
      workspace: AbsolutePath,
      cancel: Promise[Unit],
  ): Future[URI] = {
    launchVia(buildTool, target, mainClass, workspace, cancel)
  }

  def startDebugTest(
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      workspace: AbsolutePath,
      cancel: Promise[Unit],
  ): Future[URI] =
    launchTestVia(buildTool, target, testSuites, workspace, cancel)

  def compile(
      target: MbtTarget,
      workspace: AbsolutePath,
      out: String => Unit,
      err: String => Unit,
      cancel: Promise[Unit],
  ): Future[Int] = {
    val command = buildTool.mbtCompileCommand(workspace, target)
    val toolName = buildTool.executableName
    scribe.info(
      s"MBT compile via $toolName: ${redactedCommand(command)}"
    )
    val artifactId = {
      val parts = target.name.split(':')
      if (parts.length >= 2) parts(1) else target.name
    }
    runCancellable(
      command,
      workspace,
      javaHomeEnv(target),
      out,
      err,
      cancel,
      progress = Some(s"Compiling $artifactId"),
    )
  }

  def run(
      target: MbtTarget,
      mainClass: ScalaMainClass,
      workspace: AbsolutePath,
      out: String => Unit,
      err: String => Unit,
      cancel: Promise[Unit],
  ): Future[Int] = {
    val command = buildTool.mbtRunCommand(workspace, target, mainClass)
    scribe.info(
      s"MBT run session via ${buildTool.executableName}: ${redactedCommand(command)}"
    )
    runInTerminal(command, target, workspace, out, err, cancel)
  }

  private def resolveSourceFiles(
      target: MbtTarget,
      testSuites: ScalaTestSuites,
  ): Seq[AbsolutePath] =
    MbtDebugLauncher
      .listOrNil(testSuites.getSuites)
      .flatMap(s =>
        buildTargetClasses.sourceFileForMbtTestClass(s.getClassName, target.id)
      )

  private def frameworkOf(
      target: MbtTarget,
      testSuites: ScalaTestSuites,
  ): Option[TestFramework] =
    MbtDebugLauncher
      .listOrNil(testSuites.getSuites)
      .headOption
      .flatMap(s =>
        buildTargetClasses.frameworkForMbtTestClass(s.getClassName, target.id)
      )

  def test(
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      workspace: AbsolutePath,
      out: String => Unit,
      err: String => Unit,
      cancel: Promise[Unit],
  ): Future[MbtTestRunResult] = {
    val sourceFiles = resolveSourceFiles(target, testSuites)
    val commandFuture = buildTool.mbtTestCommand(
      workspace,
      target,
      testSuites,
      sourceFiles,
      frameworkOf(target, testSuites),
    )
    val toolName = buildTool.executableName
    val artifactId = {
      val parts = target.name.split(':')
      if (parts.length >= 2) parts(1) else target.name
    }
    commandFuture.flatMap { testCommand =>
      scribe.info(
        s"MBT test session via $toolName: ${redactedCommand(testCommand.arguments)}"
      )
      runInTerminal(
        testCommand.arguments,
        target,
        workspace,
        out,
        err,
        cancel,
        progress = Some(s"Testing $artifactId"),
      ).map { exitCode =>
        MbtTestRunResult(exitCode, testCommand.consumeReport())
      }
    }
  }

  private def runInTerminal(
      command: List[String],
      target: MbtTarget,
      workspace: AbsolutePath,
      out: String => Unit,
      err: String => Unit,
      cancel: Promise[Unit],
      progress: Option[String] = None,
  ): Future[Int] = {
    out(s"> ${renderCommand(command)}")
    runCancellable(
      command,
      workspace,
      javaHomeEnv(target),
      out,
      err,
      cancel,
      progress,
    )
  }

  private def renderCommand(command: List[String]): String =
    command.map(renderArgument).mkString(" ")

  private def renderArgument(argument: String): String = {
    val escaped = argument.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char if Character.isISOControl(char) => f"\\u${char.toInt}%04x"
      case char => char.toString
    }
    s"\"$escaped\""
  }

  /**
   * Runs a build-tool command and stops it when `cancel` completes or the
   * progress bar is cancelled. The progress future completes with the process,
   * so killing the process also ends the progress notification.
   */
  private def runCancellable(
      command: List[String],
      workspace: AbsolutePath,
      env: Map[String, String],
      out: String => Unit,
      err: String => Unit,
      cancel: Promise[Unit],
      progress: Option[String],
  ): Future[Int] = {
    val process = SystemProcess.run(
      command,
      workspace,
      redirectErrorOutput = false,
      env = env,
      processOut = Some(ProcessOutput.Lines(out)),
      processErr = Some(err),
    )
    val stop = () => {
      cancel.trySuccess(())
      ()
    }
    cancel.future.foreach(_ => process.cancel)
    val done = process.complete
    progress.fold(done) { message =>
      workDoneProgress.trackFuture(message, done, onCancel = Some(stop))
    }
  }

  private def launchVia(
      launcher: MbtDebugLauncher,
      target: MbtTarget,
      mainClass: ScalaMainClass,
      workspace: AbsolutePath,
      cancel: Promise[Unit],
  ): Future[URI] = {
    val command = launcher.mbtDebugCommand(
      workspace,
      target,
      mainClass,
      MbtDebugLauncher.DebugAgentFlag,
    )
    val toolName = launcher.executableName
    compile(target, workspace, scribe.info(_), scribe.warn(_), cancel).flatMap {
      _ =>
        if (cancel.isCompleted)
          Future.failed(
            new CancellationException("MBT debug session cancelled")
          )
        else
          debugConfigCreator.create(
            target.id,
            cancel,
            isTests = false,
          ) match {
            case Left(error) => Future.failed(new IllegalStateException(error))
            case Right(projectFuture) =>
              projectFuture.map { project =>
                val patched =
                  patchProjectForRun(project, target, workspace, toolName)
                scribe.info(
                  s"MBT debug session via $toolName: ${redactedCommand(command)}"
                )
                val debuggee = new BuildToolDebugAdapter(
                  Future.successful(command),
                  workspace,
                  env = javaHomeEnv(target),
                  patched,
                  userJavaHome(),
                )
                val handler = dap.DebugServer.run(
                  debuggee,
                  new MetalsDebugToolsResolver(),
                  new DebugLogger(),
                  gracePeriod =
                    Duration(debuggeeGracePeriodSeconds, TimeUnit.SECONDS),
                )
                handler.uri
              }
          }
    }
  }

  private def launchTestVia(
      launcher: MbtDebugLauncher,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      workspace: AbsolutePath,
      cancel: Promise[Unit],
  ): Future[URI] = {
    val toolName = launcher.executableName
    val sourceFiles = resolveSourceFiles(target, testSuites)
    compile(target, workspace, scribe.info(_), scribe.warn(_), cancel).flatMap {
      _ =>
        if (cancel.isCompleted)
          Future.failed(
            new CancellationException("MBT debug session cancelled")
          )
        else
          debugConfigCreator.create(
            target.id,
            cancel,
            isTests = true,
          ) match {
            case Left(error) => Future.failed(new IllegalStateException(error))
            case Right(projectFuture) =>
              projectFuture.map { project =>
                val patched =
                  patchProjectForRun(
                    project,
                    target,
                    workspace,
                    toolName,
                    isTests = true,
                  )
                val reportReader =
                  new AtomicReference[() => MbtTestReport](() =>
                    MbtTestReport.empty
                  )
                def arguments(
                    command: Future[MbtTestCommand],
                    suffix: String,
                ): Future[List[String]] =
                  command.map { testCommand =>
                    reportReader.set(testCommand.consumeReport)
                    scribe.info(
                      s"MBT test debug session via $toolName$suffix: ${redactedCommand(testCommand.arguments)}"
                    )
                    testCommand.arguments
                  }(ExecutionContext.parasitic)
                val innerDebuggee =
                  if (launcher.supportsForkedTestDebug) {
                    val testCommandWithPort =
                      launcher.mbtTestDebugCommandWithPort(
                        workspace,
                        target,
                        testSuites,
                        sourceFiles,
                        frameworkOf(target, testSuites),
                      )
                    new ForkedTestDebugAdapter(
                      port => arguments(testCommandWithPort(port), " (forked)"),
                      workspace,
                      env = javaHomeEnv(target),
                      patched,
                      userJavaHome(),
                    )
                  } else {
                    val debugAgentFlag = MbtDebugLauncher.DebugAgentFlag
                    val commandFuture = arguments(
                      launcher.mbtTestDebugCommand(
                        workspace,
                        target,
                        testSuites,
                        debugAgentFlag,
                        sourceFiles,
                        frameworkOf(target, testSuites),
                      ),
                      "",
                    )
                    new BuildToolDebugAdapter(
                      commandFuture,
                      workspace,
                      env = javaHomeEnv(target),
                      patched,
                      userJavaHome(),
                    )
                  }
                val debuggee =
                  new MbtTestResultAdapter(
                    innerDebuggee,
                    testSuites,
                    testProvider,
                    target.id,
                    consumeReport = () => reportReader.get()(),
                  )
                val handler = dap.DebugServer.run(
                  debuggee,
                  new MetalsDebugToolsResolver(),
                  new DebugLogger(),
                  gracePeriod =
                    Duration(debuggeeGracePeriodSeconds, TimeUnit.SECONDS),
                )
                handler.uri
              }
          }
    }
  }

  /**
   * Built by [[JdkSources.envVariables]] so that `JAVA_HOME` is spelled the way
   * the importer's own commands spell it: `/jdk/Home/` against `/jdk/Home` is
   * enough to restart a build tool's daemon.
   */
  private def javaHomeEnv(target: MbtTarget): Map[String, String] =
    JdkSources.envVariables(target.javaHome.orElse(userJavaHome()))

  private def redactedCommand(command: List[String]): String =
    command.headOption.getOrElse("<empty>")

  private def patchProjectForRun(
      project: DebugeeProject,
      target: MbtTarget,
      workspace: AbsolutePath,
      toolName: String,
      isTests: Boolean = false,
  ): DebugeeProject = {
    val realClassDirs =
      target.runClassDirectories(workspace, toolName, includeTests = isTests)
    if (realClassDirs.isEmpty) {
      scribe.warn(
        s"MBT debug session: no compiled output dir for $toolName target " +
          s"'${target.name}' in $workspace — breakpoints will not bind. " +
          s"The build tool must compile before the session starts, or the " +
          s"importer should set MbtNamespace.classDirectories."
      )
      project
    } else {
      val primary = target.primaryClassDirectory(workspace, toolName)
      val patchedModules = project.modules.map { m =>
        if (
          m.name == target.name &&
          m.absolutePath.toString.replace('\\', '/').contains(".metals/mbt-out")
        )
          MultiOutputModule(
            name = m.name,
            scalaVersion = m.scalaVersion,
            scalacOptions = m.scalacOptions,
            absolutePath = primary.toNIO,
            classPath = realClassDirs.map(_.toNIO),
            sourceEntries = m.sourceEntries,
          )
        else m
      }
      val patchedRunClassPath =
        (realClassDirs ++ project.runClassPath).distinct
      project.copy(
        modules = patchedModules,
        runClassPath = patchedRunClassPath.toList,
      )
    }
  }
}
