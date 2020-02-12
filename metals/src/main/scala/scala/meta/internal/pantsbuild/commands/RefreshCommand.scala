package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages
import scala.meta.internal.pantsbuild.Export

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
  def run(refresh: RefreshOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "refresh",
      refresh.projects,
      refresh.common,
      app
    ) { project =>
      SharedCommand.interpretExport(
        Export(project, refresh.open, app).copy(
          export = refresh.export,
          isCache = refresh.update
        )
      )
    }
  }
}
