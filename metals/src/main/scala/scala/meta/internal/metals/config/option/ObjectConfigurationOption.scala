package scala.meta.internal.metals.config.option

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.config.UserConfiguration

import com.google.gson.JsonObject

case class ObjectConfigurationOption[A](
    val key: String,
    val title: String,
    val description: String,
    val example: String,
    val default: String,
    val defaultValue: A,
    val subFields: List[ConfigurationOption[_, A]],
)(
    getter: UserConfiguration => A,
    setter: (UserConfiguration, A) => UserConfiguration,
) extends LensConfigurationOption[A](getter, setter) {

  def read(context: ConfigContext): A = {
    val json = context.getObject(key).getOrElse(new JsonObject)
    val nestedContext = context.nested(json, key)
    subFields.foldLeft(defaultValue) { (config, field) =>
      field.update(nestedContext, config)
    }
  }

  def write(value: A): Option[Any] = {

    def fromField(
        field: ConfigurationOption[_, A],
        value: Any,
    ): Option[(String, Any)] = {
      val keys = field.camelCaseKey.split("\\.")
      if (keys.length == 2) {
        val objectKey = keys(0)
        val enableKey = keys(1)
        Some(objectKey -> Map(enableKey -> value).asJava)
      } else {
        Some(field.camelCaseKey -> value)
      }
    }
    val entries = for (field <- subFields) yield {
      field.get(value) match {
        case None => None
        case Some(value) => fromField(field, value.toString())
        case value =>
          fromField(field, value)
      }

    }
    Some(entries.flatten.toMap.asJava)
  }
  checkInvariants()
}
