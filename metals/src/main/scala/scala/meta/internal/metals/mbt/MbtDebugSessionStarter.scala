package scala.meta.internal.metals.mbt

import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.BaseWorkDoneProgress
import scala.meta.internal.metals.debug.server.BuildToolDebugAdapter
import scala.meta.internal.metals.debug.server.DebugLogger
import scala.meta.internal.metals.debug.server.DebugeeParamsCreator
import scala.meta.internal.metals.debug.server.DebugeeProject
import scala.meta.internal.metals.debug.server.MetalsDebugToolsResolver
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.{debugadapter => dap}

class MbtDebugSessionStarter(
    debugConfigCreator: DebugeeParamsCreator,
    buildTool: MbtDebugLauncher,
    userJavaHome: () => Option[String],
    workDoneProgress: BaseWorkDoneProgress,
    debuggeeGracePeriodSeconds: Long = 5L,
)(implicit ec: ExecutionContext) {

  def start(
      target: MbtTarget,
      mainClass: ScalaMainClass,
      workspace: AbsolutePath,
  ): Future[URI] = {
    launchVia(buildTool, target, mainClass, workspace)
  }

  def compile(
      target: MbtTarget,
      workspace: AbsolutePath,
      out: String => Unit,
      err: String => Unit,
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
    workDoneProgress.trackFuture(
      s"Compiling $artifactId",
      SystemProcess
        .run(
          command,
          workspace,
          redirectErrorOutput = false,
          env = javaHomeEnv(target),
          processOut = Some(out),
          processErr = Some(err),
        )
        .complete,
    )
  }

  def run(
      target: MbtTarget,
      mainClass: ScalaMainClass,
      workspace: AbsolutePath,
      out: String => Unit,
      err: String => Unit,
  ): Future[Int] = {
    val command = buildTool.mbtRunCommand(workspace, target, mainClass)
    scribe.info(
      s"MBT run session via ${buildTool.executableName}: ${redactedCommand(command)}"
    )
    SystemProcess
      .run(
        command,
        workspace,
        redirectErrorOutput = false,
        env = javaHomeEnv(target),
        processOut = Some(out),
        processErr = Some(err),
      )
      .complete

  }

  private def launchVia(
      launcher: MbtDebugLauncher,
      target: MbtTarget,
      mainClass: ScalaMainClass,
      workspace: AbsolutePath,
  ): Future[URI] = {
    val command = launcher.mbtDebugCommand(
      workspace,
      target,
      mainClass,
      MbtDebugLauncher.DebugAgentFlag,
    )
    val toolName = launcher.executableName
    val cancelPromise = Promise[Unit]()
    debugConfigCreator.create(target.id, cancelPromise, isTests = false) match {
      case Left(error) => Future.failed(new IllegalStateException(error))
      case Right(projectFuture) =>
        projectFuture.map { project =>
          val patched = patchProjectForRun(project, target, workspace, toolName)
          scribe.info(
            s"MBT debug session via $toolName: ${redactedCommand(command)}"
          )
          val debuggee = new BuildToolDebugAdapter(
            command,
            workspace,
            env = javaHomeEnv(target),
            patched,
            userJavaHome(),
          )
          val handler = dap.DebugServer.run(
            debuggee,
            new MetalsDebugToolsResolver(),
            new DebugLogger(),
            gracePeriod = Duration(debuggeeGracePeriodSeconds, TimeUnit.SECONDS),
          )
          handler.uri
        }
    }
  }

  private def javaHomeEnv(target: MbtTarget): Map[String, String] =
    target.javaHome
      .map { raw =>
        val path =
          if (raw.startsWith("file:")) Paths.get(URI.create(raw)).toString
          else raw
        Map("JAVA_HOME" -> path)
      }
      .getOrElse(Map.empty)

  private def redactedCommand(command: List[String]): String =
    command.headOption.getOrElse("<empty>")

  private def patchProjectForRun(
      project: DebugeeProject,
      target: MbtTarget,
      workspace: AbsolutePath,
      toolName: String,
  ): DebugeeProject = {
    val realClassDirs = target.runClassDirectories(workspace, toolName)
    if (realClassDirs.isEmpty) {
      scribe.warn(
        s"MBT debug session: no compiled output dir for $toolName target " +
          s"'${target.name}' in $workspace — breakpoints will not bind. " +
          s"The build tool must compile before the session starts, or the " +
          s"importer should set MbtNamespace.classDirectory."
      )
      project
    } else {
      val primary = realClassDirs.head.toNIO
      val patchedModules = project.modules.map { m =>
        if (
          m.absolutePath.toString.replace('\\', '/').contains(".metals/mbt-out")
        )
          m.copy(absolutePath = primary)
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
