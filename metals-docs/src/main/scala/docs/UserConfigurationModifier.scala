package docs

import scala.meta.inputs.Input
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.option.ConfigurationOption
import scala.meta.internal.metals.config.option.ObjectConfigurationOption

import mdoc.Reporter
import mdoc.StringModifier

class UserConfigurationModifier extends StringModifier {
  val name = "user-config"

  /**
   * Verify in docs to avoid runtime checks in normal usage.
   */
  def validateAllOptions(): Unit = {
    val userConfigOptions = UserConfiguration.default.productElementNames.toSet

    val documentedOptions = UserConfiguration.settings.map { option =>
      option.camelCaseKey
    }.toSet
    val undocumentedOptions = userConfigOptions -- documentedOptions

    if (undocumentedOptions.nonEmpty) {
      throw new RuntimeException(
        s"Missing options (UserConfiguration names must match key): ${undocumentedOptions.mkString(", ")}"
      )
    }

    val documentedButMissing = documentedOptions -- userConfigOptions
    if (documentedButMissing.nonEmpty) {
      throw new RuntimeException(
        s"Options were documented but not used in UserConfiguration (UserConfiguration names must match key)" +
          s": ${undocumentedOptions.mkString(", ")}"
      )
    }
  }
  // TODO go over again over docs
  validateAllOptions()

  override def process(
      info: String,
      code: Input,
      reporter: Reporter,
  ): String = {

    def render(
        option: ConfigurationOption[_, _],
        topFieldKey: Option[String],
    ): String = {
      val renderedExample = topFieldKey match {
        case Some(value) =>
          s"""|    "${value}": {
              |      "${option.key}": ${option.example}
              |    }""".stripMargin
        case None =>
          s"""    "${option.key}": ${option.example}"""
      }
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
         |${renderedExample}
         |  }
         |}
         |```
         |""".stripMargin
    }

    UserConfiguration.settings
      .map {
        case option: ObjectConfigurationOption[_] =>
          option -> option.subFields

        case other => other -> Nil
      }
      .map { case (option, subFields) =>
        render(option, None) + "\n" + subFields
          .map(render(_, Some(option.key)))
          .mkString("\n")
      }
      .mkString("\n")
  }

  private def markdownDefault(option: ConfigurationOption[_, _]): String = {
    option.defaultDescription.getOrElse {
      option.default match {
        case "" => """empty string `""`."""
        case "[]" => "`[]`."
        case other => s"`$other`."
      }
    }
  }
}
