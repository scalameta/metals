package scala.meta.internal.pantsbuild.commands

package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages

object LiveshareCommand extends Command[LiveshareCommand]("liveshare") {
  override def description: Doc =
    Doc.paragraph("Open a Liveshare session for the given project")
  override def options: Doc = Messages.options(LiveshareOptions())

  override def complete(
      context: TabCompletionContext
  ): List[TabCompletionItem] =
    SharedCommand.complete(context)

  def run(info: InfoOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "open a liveshare session for",
      info.projects,
      info.common,
      app
    ) { project =>
      project.targets.foreach { target => println(target) }
      0
    }
  }
}
