package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import scala.meta.internal.pantsbuild.Export
import metaconfig.cli.Messages

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
        val project = Project.create(name, create.common, create.targets)
        SharedCommand.interpretExport(
          Export(project, create.open, app).copy(export = create.export)
        )
    }
  }
}
