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
           |    "${option.camelCaseKey}": ${option.example}
           |  }
           |}
           |```
           |""".stripMargin
      }
      .mkString("\n")
  }
}
