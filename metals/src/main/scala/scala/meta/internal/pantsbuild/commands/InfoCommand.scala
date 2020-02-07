package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages

object InfoCommand extends Command[InfoOptions]("info") {
  override def description: Doc =
    Doc.paragraph("Display information about an existing project")
  override def options: Doc = Messages.options(InfoOptions())
  def run(info: InfoOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "show information about",
      info.projects,
      info.common,
      app
    ) { project =>
      project.targets.foreach { target =>
        println(target)
      }
      0
    }
  }
}
