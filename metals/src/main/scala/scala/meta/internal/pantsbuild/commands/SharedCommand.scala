package scala.meta.internal.pantsbuild.commands

import scala.sys.process._
import scala.util.Try
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import scala.util.Failure
import scala.util.Success
import scala.meta.internal.pantsbuild.Export
import scala.meta.internal.pantsbuild.BloopPants
import scala.meta.internal.pantsbuild.MessageOnlyException
import scala.meta.internal.pantsbuild.IntelliJ
import metaconfig.cli.CliApp
import metaconfig.internal.Levenshtein
import scala.meta.internal.pc.LogMessages
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem
import bloop.launcher.LauncherMain
import java.nio.charset.StandardCharsets
import bloop.bloopgun.core.Shell
import scala.concurrent.Promise
import scala.meta.internal.metals.BuildInfo

object SharedCommand {
  def interpretExport(export: Export): Int = {
    if (!export.pants.isFile) {
      export.app.error(
        s"no Pants build detected, file '${export.pants}' does not exist. " +
          s"To fix this problem, change the working directory to the root of a Pants build."
      )
      1
    } else {
      val workspace = export.workspace
      val targets = export.targets
      val timer = new Timer(Time.system)
      val installResult =
        BloopPants.bloopInstall(export)(ExecutionContext.global)
      installResult match {
        case Failure(exception) =>
          exception match {
            case MessageOnlyException(message) =>
              export.app.error(message)
            case _ =>
              export.app.error(s"fastpass failed to run")
              exception.printStackTrace(export.app.out)
          }
          1
        case Success(count) =>
          IntelliJ.writeBsp(export.project)
          val targets = LogMessages.pluralName("Pants target", count)
          export.app.info(
            s"exported ${targets} to project '${export.project.name}' in $timer"
          )
          SwitchCommand.runSymlinkOrWarn(
            export.project,
            export.common,
            export.app,
            isStrict = false
          )
          val updatedZipkin = ZipkinUrls.updateZipkinServerUrl()
          if (updatedZipkin) {
            restartBloopServer()
          } else {
            restartOldBloopServer()
          }
          if (export.open.isEmpty) {
            OpenCommand.onEmpty(export.project, export.app)
          } else {
            OpenCommand.run(
              export.open.withProject(export.project),
              export.app
            )
          }
          0
      }
    }
  }

  def withOneProject(
      action: String,
      projects: List[String],
      common: SharedOptions,
      app: CliApp
  )(fn: Project => Int): Int =
    projects match {
      case Nil =>
        app.error(s"no projects to $action")
        1
      case name :: Nil =>
        Project.fromName(name, common) match {
          case Some(project) =>
            fn(project)
          case None =>
            SharedCommand.noSuchProject(name, app, common)
        }
      case projects =>
        app.error(
          s"expected 1 project to $action but received ${projects.length} arguments '${projects.mkString(" ")}'"
        )
        1
    }

  def noSuchProject(name: String, app: CliApp, common: SharedOptions): Int = {
    val candidates = Project.names(common)
    val closest = Levenshtein.closestCandidate(name, candidates)
    val didYouMean = closest match {
      case Some(candidate) => s"\n\tDid you mean '$candidate'?"
      case None => ""
    }
    app.error(s"project '$name' does not exist$didYouMean")
    1
  }

  def complete(
      context: TabCompletionContext,
      allowsMultipleProjects: Boolean = false
  ): List[TabCompletionItem] = {
    if (!allowsMultipleProjects & context.arguments.length > 1) {
      Nil
    } else {
      context.setting match {
        case None =>
          Project
            .fromCommon(SharedOptions())
            .map(project => TabCompletionItem(project.name))
        case Some(_) => Nil
      }
    }
  }

  /** Upgrades the Bloop server if it's known to be an old version. */
  private def restartOldBloopServer(): Unit = {
    val isOutdated = Set[String](
      "1.4.0-RC1+33-dfd03f53",
      "1.4.0-RC1"
    )
    Try {
      val version = List("bloop", "--version").!!.linesIterator
        .find(_.startsWith("bloop v"))
        .getOrElse("")
        .stripPrefix("bloop v")
      if (isOutdated(version)) {
        scribe.info(s"shutting down old version of Bloop '$version'")
        restartBloopServer()
      }
    }
  }

  private def restartBloopServer(): Unit = {
    List("bloop", "exit").!
    new LauncherMain(
      clientIn = System.in,
      clientOut = System.out,
      out = System.out,
      charset = StandardCharsets.UTF_8,
      shell = Shell.default,
      userNailgunHost = None,
      userNailgunPort = None,
      startedServer = Promise[Unit]()
    ).runLauncher(
      bloopVersionToInstall = BuildInfo.bloopVersion,
      skipBspConnection = true,
      serverJvmOptions = Nil
    )
  }
}
