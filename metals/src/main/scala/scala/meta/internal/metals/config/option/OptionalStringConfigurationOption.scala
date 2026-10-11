package scala.meta.internal.metals.config.option

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.config.UserConfiguration

case class OptionalStringConfigurationOption(
    val key: String,
    val example: String,
    val title: String,
    val description: String,
    val default: String = "",
    override val defaultDescription: Option[String] = None,
)(
    getter: UserConfiguration => Option[String],
    setter: (UserConfiguration, Option[String]) => UserConfiguration,
) extends LensConfigurationOption[Option[String]](getter, setter) {
  def read(context: ConfigContext): Option[String] =
    context.getString(key)

  def write(value: Option[String]): Option[Any] = value

  checkInvariants()
}

object OptionalStringConfigurationOption {
  def forConfig[C](
      key: String,
      defaultValue: Option[String],
      example: String,
      title: String,
      description: String,
      fromFeatureFlags: FeatureFlagProvider => Option[String] = _ => None,
      oldNames: List[String] = List.empty,
  )(
      getter: C => Option[String],
      setter: (C, Option[String]) => C,
  ): ConfigurationOption[Option[String], C] = {
    val optionKey = key
    val optionExample = example
    val optionTitle = title
    val optionDescription = description
    new ConfigurationOptionWithLens[Option[String], C](getter, setter) {
      val default: String = defaultValue.getOrElse("")

      def key: String = optionKey
      def example: String = optionExample
      def title: String = optionTitle
      def description: String = optionDescription

      def read(context: ConfigContext): Option[String] = {
        context
          .getString(key)
          .orElse(oldNames.flatMap(name => context.getString(name)).headOption)
          .orElse(fromFeatureFlags(context.featureFlags))
          .orElse(defaultValue)
      }
      def write(value: Option[String]): Option[Any] = value
      checkInvariants()
    }
  }
}
