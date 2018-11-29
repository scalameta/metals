package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input
import scala.meta.internal.metals.ServerCommands

class CommandsModifiers extends StringModifier {
  override val name: String = "commands"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    ServerCommands.all
      .map { command =>
        s"""### ${command.title}
           |**Command**: `"${command.id}"`
           |
           |**Params**: `{}`
           |
           |${command.description}
           |""".stripMargin
      }
      .mkString("\n")
  }

}
