package scala.meta.internal.metals.config.option

import scala.meta.internal.metals.config.UserConfiguration

case class ParsedStringConfigurationOption[A](
    val key: String,
    val title: String,
    val description: String,
    val example: String,
    val default: String,
    fallback: A,
    wrapJsonError: Boolean = true,
    override val values: Option[List[String]] = None,
)(
    getter: UserConfiguration => A,
    setter: (UserConfiguration, A) => UserConfiguration,
)(
    parse: (Option[String], ConfigContext) => Either[String, A],
    serialize: A => Any,
) extends LensConfigurationOption[A](getter, setter) {
  def read(context: ConfigContext): A =
    parse(context.getString(key), context) match {
      case Right(value) => value
      case Left(error) =>
        val message = if (wrapJsonError) s"json error: $error" else error
        context.addError(message)
        fallback
    }

  def write(value: A): Option[Any] = Some(serialize(value))

  checkInvariants()
}
