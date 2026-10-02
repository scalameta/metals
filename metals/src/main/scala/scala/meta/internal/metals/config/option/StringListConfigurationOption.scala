package scala.meta.internal.metals.config.option

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.config.UserConfiguration

case class StringListConfigurationOption(
    val key: String,
    val example: String,
    val title: String,
    val description: String,
    val default: String = "",

    defaultValue: List[String] = Nil,
    fromFeatureFlag: FeatureFlagProvider => List[String] = _ => Nil,
    validValues: List[String] = Nil,
)(
    getter: UserConfiguration => List[String],
    setter: (UserConfiguration, List[String]) => UserConfiguration,
) extends LensConfigurationOption[List[String]](getter, setter) {
  override val isArray: Boolean = true

  def read(context: ConfigContext): List[String] = {
    val values =
      (context.getStringList(key).getOrElse(defaultValue) ++ fromFeatureFlag(
        context.featureFlags
      )).distinct
    if (validValues.nonEmpty) {
      val invalid = values.filterNot(validValues.contains)
      if (invalid.nonEmpty) {
        context.addError(
          s"invalid value '$invalid' for $key. Valid values are ${validValues.mkString(", ")}"
        )
      }
      values.filter(validValues.contains)
    } else {
      values
    }
  }

  def write(value: List[String]): Option[Any] = Some(value.asJava)

  checkInvariants()
}
