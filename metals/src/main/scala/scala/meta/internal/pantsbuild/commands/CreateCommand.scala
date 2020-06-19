package scala.meta.internal.pantsbuild.commands

import java.io.File

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pantsbuild.Export
import scala.meta.io.AbsolutePath

import metaconfig.cli.CliApp
import metaconfig.cli.Command
import metaconfig.cli.Messages
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem
import org.typelevel.paiges.Doc

object CreateCommand extends Command[CreateOptions]("create") {
  override def description: Doc =
    Doc.paragraph("Create a new project from a list of Pants targets")
  override def usage: Doc =
    Doc.text("fastpass create [OPTIONS] [TARGETS ...]")
  override def options: Doc = Messages.options(CreateOptions())
  override def examples: Doc =
    Doc.intercalate(
      Doc.line,
      List(
        "# Create project with custom name from two Pants targets",
        "fastpass create --name PROJECT_NAME TARGETS1:: TARGETS2::",
        "",
        "# Create project with an auto-generated name and launch IntelliJ",
        "fastpass create --intellij TARGETS::"
      ).map(Doc.text)
    )
  override def complete(
      context: TabCompletionContext
  ): List[TabCompletionItem] = {
    context.setting match {
      case None =>
        completeTargetSpec(context, PathIO.workingDirectory)
      case _ =>
        Nil
    }
  }
  def run(create: CreateOptions, app: CliApp): Int = {
    val name = create.actualName
    Project.fromName(name, create.common) match {
      case Some(value) =>
        app.error(
          s"can't create project named '${name}' because it already exists." +
            s"\n\tTo refresh the project run: fastpass refresh ${name}"
        )
        1
      case None =>
        val project = Project.create(
          name,
          create.common,
          create.targets,
          create.export.disableSources
        )
        SharedCommand.interpretExport(
          Export(project, create.open, app).copy(export = create.export)
        )
    }
  }

  private def completeTargetSpec(
      context: TabCompletionContext,
      cwd: AbsolutePath
  ): List[TabCompletionItem] = {
    val path =
      context.last.split(File.separatorChar).foldLeft(cwd) {
        case (dir, "") => dir
        case (dir, name) => dir.resolve(name)
      }
    val toList =
      if (
        context.last.isEmpty ||
        context.last.endsWith(File.separator)
      ) path
      else path.parent
    toList.list
      .filter(_.isDirectory)
      .filter(!_.filename.startsWith("."))
      .map(name =>
        name
          .toRelative(cwd)
          .toURI(isDirectory = false)
          .toString() + "::"
      )
      .map(name => TabCompletionItem(name))
      .toBuffer
      .toList
  }
}
