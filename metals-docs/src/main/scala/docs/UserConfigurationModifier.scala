package docs

import scala.meta.inputs.Input
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.UserConfigurationOption

import mdoc.Reporter
import mdoc.StringModifier

class UserConfigurationModifier extends StringModifier {
  val name = "user-config"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter,
  ): String = {
    UserConfiguration.options
      .map { option =>
        s"""
           |### ${option.title}
           |
           |${option.description}
           |
           |**Default**: ${markdownDefault(option)}
           |
           |**Example**:
           |```json
           |{
           |  "metals": {
           |    "${option.camelCaseKey}": ${option.example}
           |  }
           |}
           |```
           |""".stripMargin
      }
      .mkString("\n")
  }

  private def markdownDefault(option: UserConfigurationOption): String = {
    option.defaultDescription.getOrElse {
      option.default match {
        case "" => """empty string `""`."""
        case "[]" => "`[]`."
        case other => s"`$other`."
      }
    }
  }
}
