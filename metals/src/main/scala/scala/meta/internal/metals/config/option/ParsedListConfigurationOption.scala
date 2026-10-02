package scala.meta.internal.metals.config.option

import scala.meta.internal.metals.config.UserConfiguration

case class ParsedListConfigurationOption[A](
    val key: String,
    val title: String,
    val description: String,
    val example: String,
    val default: String,
    fallback: A,
)(
    getter: UserConfiguration => A,
    setter: (UserConfiguration, A) => UserConfiguration,
)(
    parse: (Option[List[String]], ConfigContext) => Either[String, A],
    serialize: A => Any,
) extends LensConfigurationOption[A](getter, setter) {
  override val isArray: Boolean = true

  def read(context: ConfigContext): A =
    parse(context.getStringList(key), context) match {
      case Right(value) => value
      case Left(error) =>
        context.addError(s"json error: $error")
        fallback
    }

  def write(value: A): Option[Any] = Some(serialize(value))

  checkInvariants()
}
