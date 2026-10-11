package scala.meta.internal.metals.config.option

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.JsonParser.XtensionSerializedAsOption
import scala.meta.internal.metals.config.UserConfiguration

case class BooleanConfigurationOption(
    val key: String,
    defaultValue: Boolean,
    val example: String,
    val title: String,
    val description: String,
    fromFeatureFlags: FeatureFlagProvider => Option[Boolean] = _ => None,
    oldNames: List[String] = List.empty,
)(
    getter: UserConfiguration => Boolean,
    setter: (UserConfiguration, Boolean) => UserConfiguration,
) extends LensConfigurationOption[Boolean](getter, setter) {
  override val isBoolean: Boolean = true
  val default: String = defaultValue.toString

  def read(context: ConfigContext): Boolean =
    context
      .getBoolean(key)
      .orElse(oldNames.flatMap(name => context.getBoolean(name)).headOption)
      .orElse(fromFeatureFlags(context.featureFlags))
      .getOrElse(defaultValue)

  def write(value: Boolean): Option[Any] = Some(value)

  checkInvariants()
}

object BooleanConfigurationOption {
  def forConfig[C](
      key: String,
      defaultValue: Boolean,
      example: String,
      title: String,
      description: String,
      fromFeatureFlags: FeatureFlagProvider => Option[Boolean] = _ => None,
  )(
      getter: C => Boolean,
      setter: (C, Boolean) => C,
  ): ConfigurationOption[Boolean, C] = {
    val optionKey = key
    val optionExample = example
    val optionTitle = title
    val optionDescription = description
    new ConfigurationOptionWithLens[Boolean, C](getter, setter) {
      override val isBoolean: Boolean = true
      val default: String = defaultValue.toString

      def key: String = optionKey
      def example: String = optionExample
      def title: String = optionTitle
      def description: String = optionDescription

      def read(context: ConfigContext): Boolean = {
        val keys = key.split("\\.")
        if (keys.length == 2) {
          val objectKey = keys(0)
          val enableKey = keys(1)
          context
            .getObject(objectKey)
            .flatMap(_.getBooleanOption(enableKey))
            .orElse(fromFeatureFlags(context.featureFlags))
            .getOrElse(defaultValue)
        } else if (keys.length == 1)
          context
            .getBoolean(key)
            .orElse(fromFeatureFlags(context.featureFlags))
            .getOrElse(defaultValue)
        else throw new IllegalArgumentException(s"Invalid key: $key")
      }
      def write(value: Boolean): Option[Any] = {
        Some(value)
      }
      checkInvariants()
    }
  }
}
