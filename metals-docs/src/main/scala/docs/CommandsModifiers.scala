package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Command
import scala.meta.internal.metals.ServerCommands

class CommandsModifiers extends StringModifier {
  override val name: String = "commands"

  def format(commands: List[Command]): String = {
    commands
      .map { command =>
        s"""### ${command.title}
           |**Command**: `"${command.id}"`
           |
           |**Arguments**: ${command.arguments}
           |
           |${command.description}
           |""".stripMargin
      }
      .mkString("\n")
  }
  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    info match {
      case "client" =>
        format(ClientCommands.all)
      case "server" =>
        format(ServerCommands.all)
      case _ =>
        reporter.error(
          s"Unknown command '$info', expected 'server' or 'client'"
        )
        ""
    }
  }

}
