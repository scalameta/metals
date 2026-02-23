package scala.meta.internal.metals

/**
 * A setting that users can configure via LSP workspace/didChangeConfiguration.
 *
 * @see `UserConfiguration.options` for available options.
 */
case class UserConfigurationOption(
    key: String,
    default: String,
    example: String,
    title: String,
    description: String,
    isBoolean: Boolean = false,
    isArray: Boolean = false,
) {
  assert(
    !(isArray && isBoolean),
    "isArray and isBoolean cannot be true at the same time",
  )

  def headerID: String = {
    title.toLowerCase().replace(' ', '-').replace("'", " ")
  }
  def camelCaseKey: String =
    key.split('-').toList match {
      case head :: tail => head ++ tail.flatMap(_.capitalize)
      case _ => key
    }
}
