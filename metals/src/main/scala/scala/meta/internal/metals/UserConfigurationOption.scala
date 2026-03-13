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
    values: Option[List[String]] = None,
    defaultDescription: Option[String] = None,
) {
  assert(
    !(isArray && isBoolean),
    "isArray and isBoolean cannot be true at the same time",
  )
  assert(
    values.forall(vs => vs.contains(default)),
    "default must be one of values when values is defined",
  )
  assert(
    values.isEmpty || (!isArray && !isBoolean),
    "values cannot be combined with isArray/isBoolean flags",
  )

  def headerID: String = {
    title.toLowerCase().replace(' ', '-').replace("'", " ")
  }
  def camelCaseKey: String =
    key.split('-').toList match {
      case head :: tail => head ++ tail.flatMap(_.capitalize)
      case _ => key
    }

  def oneLiner: String = {
    val tpe = values match {
      case Some(vs) => vs.mkString("[", ",", "]")
      case None =>
        if (isBoolean) "boolean"
        else if (isArray) "array"
        else "string"
    }
    val displayDefault = if (default.isEmpty) "\"\"" else default
    f"$key%-44s $tpe%-30s $displayDefault%-15s $title"
  }
}
