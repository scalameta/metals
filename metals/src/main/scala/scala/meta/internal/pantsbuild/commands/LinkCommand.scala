package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages
import java.nio.file.Files

object LinkCommand extends Command[LinkOptions]("link") {
  override def options: Doc = Messages.options(LinkOptions())
  override def description: Doc =
    Doc.paragraph("Symlink the Bloop build into the workspace directory")
  override def usage: Doc = Doc.text("fastpass link PROJECT_NAME")
  override def examples: Doc =
    Doc.intercalate(
      Doc.line,
      List(
        "fastpass link PROJECT_NAME",
        "# List all Bloop targets in the newly linked project",
        "bloop projects"
      ).map(Doc.text)
    )

  def run(link: LinkOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "link",
      link.projects,
      link.common,
      app
    ) { project =>
      runSymblinkOrWarn(project, link.common, app, isStrict = true)
    }
  }

  def runSymblinkOrWarn(
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
            s"project '${project.name}' is already linked, $runBloopProjects"
          )
        }
        0
      } else {
        runSymlink(project, common)
        app.info(s"linked project '${project.name}', $runBloopProjects")
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
      val message = s"unable to link project '${project.name}' because '$bloopDirectory' is a directory. " +
        s"To fix this problem run: " +
        s"\n\trm -rf $relpath" +
        s"\n\tfastpass link ${project.name}"
      if (isError) app.error(message)
      else app.warn(message)
      true
    } else {
      false
    }
  }
}
