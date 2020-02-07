package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.Command
import metaconfig.cli.CliApp
import org.typelevel.paiges.Doc
import metaconfig.cli.Messages

object ListCommand extends Command[ListProjects]("list") {
  override def description: Doc =
    Doc.paragraph("List all existing projects")
  override def options: Doc = Messages.options(ListProjects())
  def run(list: ListProjects, app: CliApp): Int = {
    Project.fromCommon(list.common).foreach { project =>
      println(project.name)
    }
    0
  }
}
