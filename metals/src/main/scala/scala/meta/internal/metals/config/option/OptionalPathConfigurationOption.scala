package scala.meta.internal.metals.config.option

import java.nio.file.Paths

import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.io.AbsolutePath

case class OptionalPathConfigurationOption(
    val key: String,
    val example: String,
    val title: String,
    val description: String,
    override val defaultDescription: Option[String] = None,
    val default: String = "",
)(
    getter: UserConfiguration => Option[AbsolutePath],
    setter: (
        UserConfiguration,
        Option[AbsolutePath],
    ) => UserConfiguration,
) extends LensConfigurationOption[Option[AbsolutePath]](
      getter,
      setter,
    ) {
  def read(
      context: ConfigContext
  ): Option[AbsolutePath] =
    context.getString(key).map(AbsolutePath(_))

  def write(
      value: Option[AbsolutePath]
  ): Option[Any] =
    value.map(_.toString)

  checkInvariants()
}

object OptionalPathConfigurationOption {
  def forConfig[C](
      key: String,
      defaultValue: Option[AbsolutePath],
      example: String,
      title: String,
      description: String,
  )(
      getter: C => Option[AbsolutePath],
      setter: (C, Option[AbsolutePath]) => C,
  ): ConfigurationOption[Option[AbsolutePath], C] = {
    val optionKey = key
    val optionExample = example
    val optionTitle = title
    val optionDescription = description
    new ConfigurationOptionWithLens[Option[AbsolutePath], C](getter, setter) {
      val default: String = defaultValue.map(_.toString).getOrElse("")
      def key: String = optionKey
      def example: String = optionExample
      def title: String = optionTitle
      def description: String = optionDescription

      def read(context: ConfigContext): Option[AbsolutePath] = {
        context
          .getString(camelCaseKey)
          .map(filePath => AbsolutePath(Paths.get(filePath)))
          .orElse(defaultValue)
      }
      def write(value: Option[AbsolutePath]): Option[Any] =
        value.map(_.toString)
      checkInvariants()
    }
  }
}
