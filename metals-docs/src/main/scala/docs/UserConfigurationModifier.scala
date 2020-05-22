package docs

import scala.meta.inputs.Input
import scala.meta.internal.metals.UserConfiguration

import mdoc.Reporter
import mdoc.StringModifier

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
               |**Default**: ${option.default}
               |
               |**Note**: this property can also be defined as user configuration option [${option.title}](#${option.headerID}).
               |""".stripMargin
          }
          .mkString("\n")
      case "lsp-config-default" =>
        UserConfiguration.options
          .map { option =>
            s"""
               |### ${option.title}
               |
               |${option.description}
               |
               |**Default**: ${option.default}
               |
               |**Example**:
               |```json
               |{
               |  "metals": {
               |    "${option.key}": "${option.example}"
               |  }
               |}
               |```
               |""".stripMargin
          }
          .mkString("\n")
      case "lsp-config-coc" =>
        UserConfiguration.options
          .map { option =>
            s"""
               |### ${option.title}
               |
               |${option.description}
               |
               |**Default**: ${option.default}
               |
               |**Example**:
               |```json
               |{
               |  "metals.${option.camelCaseKey}": "${option.example}"
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
