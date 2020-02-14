package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages
import scala.meta.internal.pantsbuild.Export
import metaconfig.cli.{TabCompletionContext, TabCompletionItem}

object RefreshCommand extends Command[RefreshOptions]("refresh") {
  override def description: Doc = Doc.paragraph("Refresh an existing project")
  override def options: Doc = Messages.options(RefreshOptions())
  override def examples: Doc =
    Doc.intercalate(
      Doc.line,
      List(
        "# Refresh a project and launch IntelliJ after the refresh completes",
        "fastpass refresh --intellij PROJECT_NAME"
      ).map(Doc.text)
    )
  override def complete(
      context: TabCompletionContext
  ): List[TabCompletionItem] =
    SharedCommand.complete(context, allowsMultipleProjects = true)
  def run(refresh: RefreshOptions, app: CliApp): Int = {
    val projects = Project.fromCommon(refresh.common)
    val errors = refresh.projects.map { projectName =>
      projects.find(_.name == projectName) match {
        case Some(project) =>
          SharedCommand.interpretExport(
            Export(project, refresh.open, app).copy(
              export = refresh.export,
              isCache = refresh.update
            )
          )
        case None =>
          SharedCommand.noSuchProject(projectName, app, refresh.common)
      }
    }
    errors.sum
  }
}
