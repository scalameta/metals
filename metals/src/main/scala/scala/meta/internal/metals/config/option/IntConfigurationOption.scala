package scala.meta.internal.metals.config.option

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.config.UserConfiguration

case class IntConfigurationOption(
    val key: String,
    defaultValue: Int,
    val example: String,
    val title: String,
    val description: String,
    fromFeatureFlag: FeatureFlagProvider => Option[Int] =
      (_: FeatureFlagProvider) => None,
)(
    getter: UserConfiguration => Int,
    setter: (UserConfiguration, Int) => UserConfiguration,
) extends LensConfigurationOption[Int](getter, setter) {

  val default: String = defaultValue.toString
  override val isNumber: Boolean = true
  def read(context: ConfigContext): Int =
    context.getInt(key) match {
      case Some(value) => value
      case None => fromFeatureFlag(context.featureFlags).getOrElse(defaultValue)
    }

  def write(value: Int): Option[Any] = Some(value)

  checkInvariants()
}

object IntConfigurationOption {
  def forConfig[C](
      key: String,
      defaultValue: Int,
      example: String,
      title: String,
      description: String,
      fromFeatureFlag: FeatureFlagProvider => Option[Int] =
        (_: FeatureFlagProvider) => None,
  )(
      getter: C => Int,
      setter: (C, Int) => C,
  ): ConfigurationOption[Int, C] = {
    val optionKey = key
    val optionExample = example
    val optionTitle = title
    val optionDescription = description
    new ConfigurationOptionWithLens[Int, C](getter, setter) {
      override val isNumber: Boolean = true
      val default: String = defaultValue.toString

      def key: String = optionKey
      def example: String = optionExample
      def title: String = optionTitle
      def description: String = optionDescription

      def read(context: ConfigContext): Int =
        context
          .getInt(key)
          .orElse(fromFeatureFlag(context.featureFlags))
          .getOrElse(defaultValue)

      def write(value: Int): Option[Any] = Some(value)

      checkInvariants()
    }
  }
}
