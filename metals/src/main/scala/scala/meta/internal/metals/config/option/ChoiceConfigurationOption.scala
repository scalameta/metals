package scala.meta.internal.metals.config.option

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.config.UserConfiguration

case class ChoiceConfigurationOption[A](
    val key: String,
    defaultValue: A,
    choices: List[(String, A)],
    val example: String,
    val title: String,
    val description: String,
    fromFeatureFlag: FeatureFlagProvider => Option[A] =
      (_: FeatureFlagProvider) => None,
)(
    getter: UserConfiguration => A,
    setter: (UserConfiguration, A) => UserConfiguration,
) extends LensConfigurationOption[A](getter, setter) {
  override val values: Option[List[String]] = Some(
    choices.map(_._2.toString())
  )

  def default: String = defaultValue.toString
  def read(context: ConfigContext): A =
    context.getString(key) match {
      case None => fromFeatureFlag(context.featureFlags).getOrElse(defaultValue)
      case Some(raw) =>
        // ignore cases, accept any variation of the name
        def normalize(name: String): String = name.trim().toLowerCase()
        val normalized = normalize(raw)
        choices.collectFirst {
          case (name, value) if normalize(name) == normalized => value
        } match {
          case Some(value) => value
          case None =>
            context.addError(
              s"Invalid $key '$raw'. Valid values are: ${choices.map(_._1).mkString(", ")}"
            )
            defaultValue
        }
    }

  def write(value: A): Option[Any] = Some(value.toString().toLowerCase())

  checkInvariants()
}
