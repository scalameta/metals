package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages
import java.nio.file.Files
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem

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
      def runBloopProjects =
        "to see the list of exported Pants targets run: bloop projects"
      if (isUnchanged) {
        if (isStrict) {
          app.info(
            s"project '${project.name}' is already active, $runBloopProjects"
          )
        }
        0
      } else {
        runSymlink(project, common)
        app.info(s"switched to project '${project.name}', $runBloopProjects")
        0
      }
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
      val link = workspace.resolve(".scalafmt.conf")
      // Configuration file may be symbolic link.
      val relpath =
        if (Files.isSymbolicLink(link)) Files.readSymbolicLink(link)
        else link
      // Symbolic link may be relative to workspace directory.
      if (relpath.isAbsolute()) relpath
      else workspace.resolve(relpath)
    }
    val outScalafmt = out.resolve(".scalafmt.conf")
    if (!out.startsWith(workspace) &&
      Files.exists(inScalafmt) && {
        !Files.exists(outScalafmt) ||
        Files.isSymbolicLink(outScalafmt)
      }) {
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
    if (Files.isDirectory(bloopDirectory) &&
      !Files.isSymbolicLink(bloopDirectory)) {
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
