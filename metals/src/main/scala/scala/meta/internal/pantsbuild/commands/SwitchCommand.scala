package scala.meta.internal.pantsbuild.commands

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import metaconfig.cli.CliApp
import metaconfig.cli.Command
import metaconfig.cli.Messages
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem
import org.typelevel.paiges.Doc

object SwitchCommand extends Command[SwitchOptions]("switch") {
  override def options: Doc = Messages.options(SwitchOptions())
  override def description: Doc =
    Doc.paragraph("Activate an existing project")
  override def usage: Doc = Doc.text("fastpass switch PROJECT_NAME")
  override def examples: Doc =
    Doc.intercalate(
      Doc.line,
      List(
        "fastpass switch PROJECT_NAME",
        "# List all Bloop targets in the newly activated project",
        "bloop projects"
      ).map(Doc.text)
    )

  override def complete(
      context: TabCompletionContext
  ): List[TabCompletionItem] =
    SharedCommand.complete(context, allowsMultipleProjects = true)

  def run(switch: SwitchOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "switch",
      switch.projects,
      switch.common,
      app
    ) { project =>
      runSymlinkOrWarn(project, switch.common, app, isStrict = true)
    }
  }

  def runSymlinkOrWarn(
      project: Project,
      common: SharedOptions,
      app: CliApp,
      isStrict: Boolean
  ): Int = {
    if (warnBloopDirectory(project, common, app, isStrict)) {
      1
    } else {
      val isUnchanged =
        Files.isSymbolicLink(common.bloopDirectory) &&
          Files.readSymbolicLink(common.bloopDirectory) ==
            project.root.bloopRoot.toNIO
      if (isUnchanged) {
        if (isStrict) {
          app.info(
            s"project '${project.name}' is already active${runBloopProjects(project.common)}"
          )
        }
        0
      } else {
        val isCreated = !Files.isSymbolicLink(project.common.bloopDirectory)
        runSymlink(project, common)
        val verb = if (isCreated) "created" else "switched to"
        app.info(
          s"$verb project '${project.name}'${runBloopProjects(project.common)}"
        )
        0
      }
    }
  }

  private def runBloopProjects(common: SharedOptions): String = {
    val isBuildExported =
      Files.isSymbolicLink(common.bloopDirectory) &&
        // Check that at least some files have been generated in `.bloop/*`
        AbsolutePath(Files.readSymbolicLink(common.bloopDirectory)).list
          .exists(_.filename != "bloop.settings.json")
    if (isBuildExported) {
      ", to see the list of exported Pants targets run: bloop projects"
    } else {
      ""
    }
  }

  private def runSymlink(
      project: Project,
      common: SharedOptions
  ): Unit = {
    val workspace = common.workspace
    val workspaceBloop = common.bloopDirectory
    val out = project.root.bspRoot.toNIO
    val outBloop = project.root.bloopRoot.toNIO

    if (!Files.exists(workspaceBloop) || Files.isSymbolicLink(workspaceBloop)) {
      Files.deleteIfExists(workspaceBloop)
      Files.createSymbolicLink(workspaceBloop, outBloop)
    }

    val inScalafmt = {
      var link = workspace.resolve(".scalafmt.conf")
      // Configuration file may be symbolic link.
      while (Files.isSymbolicLink(link)) {
        link = Files.readSymbolicLink(link)
      }
      // Symbolic link may be relative to workspace directory.
      if (link.isAbsolute()) link
      else workspace.resolve(link)
    }
    val outScalafmt = out.resolve(".scalafmt.conf")
    if (
      !out.startsWith(workspace) &&
      Files.exists(inScalafmt) && {
        !Files.exists(outScalafmt) ||
        Files.isSymbolicLink(outScalafmt)
      }
    ) {
      Files.deleteIfExists(outScalafmt)
      Files.createSymbolicLink(outScalafmt, inScalafmt)
    }
  }

  private def warnBloopDirectory(
      project: Project,
      common: SharedOptions,
      app: CliApp,
      isError: Boolean
  ): Boolean = {
    val bloopDirectory = common.bloopDirectory
    if (
      Files.isDirectory(bloopDirectory) &&
      !Files.isSymbolicLink(bloopDirectory)
    ) {
      val relpath = app.workingDirectory.relativize(bloopDirectory)
      val message =
        s"unable to link project '${project.name}' because '$bloopDirectory' is a directory. " +
          s"To fix this problem run: " +
          s"\n\trm -rf $relpath" +
          s"\n\tfastpass switch ${project.name}"
      if (isError) app.error(message)
      else app.warn(message)
      true
    } else {
      false
    }
  }
}
