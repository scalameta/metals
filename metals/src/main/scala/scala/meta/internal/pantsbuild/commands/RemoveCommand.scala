package scala.meta.internal.pantsbuild.commands

import scala.meta.internal.metals.RecursivelyDelete

import metaconfig.cli.CliApp
import metaconfig.cli.Command
import metaconfig.cli.Messages
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem
import org.typelevel.paiges.Doc

object RemoveCommand extends Command[RemoveOptions]("remove") {
  override def description: Doc = Doc.paragraph("Delete existing projects")
  override def options: Doc = Messages.options(RemoveOptions())
  override def examples: Doc =
    Doc.text("fastpass remove PROJECT_NAME1 PROJECT_NAME2")
  override def complete(
      context: TabCompletionContext
  ): List[TabCompletionItem] =
    SharedCommand.complete(context, allowsMultipleProjects = true)
  def run(remove: RemoveOptions, app: CliApp): Int = {
    val errors: List[Int] = remove.projects.map { name =>
      Project.fromName(name, remove.common) match {
        case Some(value) =>
          app.info(s"removing directory '${value.root.bspRoot}'")
          RecursivelyDelete(value.root.bspRoot)
          0
        case None =>
          app.error(s"project '$name' does not exist")
          1
      }
    }
    errors.sum
  }
}
