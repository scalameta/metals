package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import scala.meta.internal.pantsbuild.IntelliJ
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages
import scala.meta.internal.pantsbuild.VSCode

object OpenCommand extends Command[OpenOptions]("open") {
  override def description: Doc =
    Doc.paragraph("Launch IntelliJ or VS Code with the given project.")
  override def usage: Doc =
    Doc.text("fastpass open [OPTIONS] [PROJECT_NAME ...]")
  override def options: Doc =
    Messages.options(OpenOptions())
  override def examples: Doc =
    Doc.text("fastpass open --intellij PROJECT_NAME")
  def onEmpty(project: Project, app: CliApp): Unit = {
    import MetaconfigEnrichments._
    app.info(
      s"to open the project in IntelliJ run: fastpass open --intellij ${project.name}"
    )
    app.info(
      s"to open the project in VS Code run:  fastpass open --vscode ${project.name}"
    )
  }
  def run(open: OpenOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "open",
      open.projects,
      open.common,
      app
    ) { project =>
      LinkCommand.runSymblinkOrWarn(project, open.common, app, isStrict = false)
      if (open.strict && open.isEmpty) {
        app.error(
          s"can't open project '${project.name}' since no editor is provided"
        )
        onEmpty(project, app)
        1
      } else {
        if (open.intellij) {
          IntelliJ.launch(project)
        }
        if (open.vscode) {
          VSCode.launch(project)
        }
        0
      }
    }
  }
}
