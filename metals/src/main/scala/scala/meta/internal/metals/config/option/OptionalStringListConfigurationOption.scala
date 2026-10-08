package scala.meta.internal.metals.config.option

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.config.UserConfiguration

case class OptionalStringListConfigurationOption(
    val key: String,
    val example: String,
    val title: String,
    val description: String,
    val default: String,
)(
    getter: UserConfiguration => Option[List[String]],
    setter: (UserConfiguration, Option[List[String]]) => UserConfiguration,
) extends LensConfigurationOption[Option[List[String]]](getter, setter) {
  override val isArray: Boolean = true

  def read(context: ConfigContext): Option[List[String]] =
    context.getStringList(key)

  def write(value: Option[List[String]]): Option[Any] =
    value.map(_.asJava)

  checkInvariants()
}
