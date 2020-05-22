package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.CliApp
import metaconfig.cli.Command
import metaconfig.cli.Messages
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem
import org.typelevel.paiges.Doc

object InfoCommand extends Command[InfoOptions]("info") {
  override def description: Doc =
    Doc.paragraph("Display information about an existing project")
  override def options: Doc = Messages.options(InfoOptions())
  override def complete(
      context: TabCompletionContext
  ): List[TabCompletionItem] =
    SharedCommand.complete(context)
  def run(info: InfoOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "show information about",
      info.projects,
      info.common,
      app
    ) { project =>
      project.targets.foreach { target => println(target) }
      0
    }
  }
}
