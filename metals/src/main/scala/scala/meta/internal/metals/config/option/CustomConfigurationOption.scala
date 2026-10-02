package scala.meta.internal.metals.config.option

import scala.meta.internal.metals.StringCase
import scala.meta.internal.metals.config.UserConfiguration

/** A setting whose JSON shape does not fit the default option types. */
case class CustomConfigurationOption[A](
    val key: String,
    val title: String,
    val description: String,
    val example: String,
    val default: String,
    override val isBoolean: Boolean = false,
    override val isArray: Boolean = false,
    override val values: Option[List[String]] = None,
    override val defaultDescription: Option[String] = None,
    serializationKeyOverride: Option[String] = None,
    subFields: List[UserConfigurationOption[_]] = Nil,
)(
    getter: UserConfiguration => A,
    setter: (UserConfiguration, A) => UserConfiguration,
)(
    readValue: ConfigContext => A,
    writeValue: A => Option[Any],
) extends LensConfigurationOption[A](getter, setter) {
  override def serializationKey: String =
    serializationKeyOverride.getOrElse(StringCase.kebabToCamel(key))

  def read(context: ConfigContext): A = readValue(context)

  def write(value: A): Option[Any] = writeValue(value)

  checkInvariants()
}
