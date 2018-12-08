package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input
import scala.meta.internal.metals.UserConfiguration

class UserConfigurationModifier extends StringModifier {
  val name = "user-config"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    info match {
      case "system-property" =>
        UserConfiguration.options
          .map { option =>
            s"""
               |### `-Dmetals.${option.key}`
               |
               |${option.description}
               |
               |Default: ${option.default}
               |
               |See also [user configuration `"${option.key}"`](#${option.key}).
               |""".stripMargin
          }
          .mkString("\n")
      case "lsp-config" =>
        UserConfiguration.options
          .map { option =>
            s"""
               |### `"${option.key}"`
               |
               |${option.description}
               |
               |**Default**: ${option.default}
               |
               |**Example**:
               |```json
               |{
               |  "${option.key}": "${option.example}"
               |}
               |```
               |""".stripMargin
          }
          .mkString("\n")
      case els =>
        reporter.error(s"unknown user-config '$els'")
        ""
    }
  }
}
