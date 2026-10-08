package scala.meta.internal.metals.config.option

import java.util.Properties

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StringCase
import scala.meta.internal.metals.config.UserConfiguration

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * One Metals setting.
 *
 * Reading JSON, writing the debug configuration, and user-facing
 * documentation all come from the same definition. To add a setting, append
 * one [[UserConfigurationOption]] to [[UserConfigurationOptions.all]] and a
 * field on [[UserConfiguration]]. To remove one, delete those.
 *
 * Simple settings use [[BooleanConfigurationOption]],
 * [[OptionalStringConfigurationOption]], and the other default
 * implementations. Settings with their own value type, such as
 * [[RangeFormattingProviders]], use a custom option.
 */
trait ConfigurationOption[A, C] {
  def key: String
  def title: String
  def description: String
  def example: String

  /** Printed default in `metals config` and the website docs. */
  def default: String
  def defaultDescription: Option[String] = None
  def isBoolean: Boolean = false
  def isArray: Boolean = false
  def isNumber: Boolean = false
  def values: Option[List[String]] = None

  /** Key used by [[UserConfiguration.toString]]. */
  def serializationKey: String = StringCase.kebabToCamel(key)

  def read(context: ConfigContext): A
  def write(value: A): Option[Any]
  def get(config: C): A
  def update(config: C, value: A): C

  final def update(
      context: ConfigContext,
      config: C,
  ): C =
    update(config, read(context))

  final def jsonEntry(config: C): Option[(String, Any)] =
    write(get(config)).map(serializationKey -> _)

  def camelCaseKey: String =
    key.split('-').toList match {
      case head :: tail => head ++ tail.flatMap(_.capitalize)
      case _ => key
    }

  def oneLiner: String = oneLiner(key)

  def oneLiner(displayKey: String): String = {
    val tpe = values match {
      case Some(choices) => choices.mkString("[", ",", "]")
      case None =>
        if (isBoolean) "boolean"
        else if (isArray) "array"
        else if (isNumber) "number"
        else "string"
    }
    val displayDefault = if (default.isEmpty) "\"\"" else default
    UserConfigurationOption.printOneLiner(
      displayKey,
      tpe,
      displayDefault,
      title,
    )
  }

  protected def checkInvariants(): Unit = {
    assert(
      !(isArray && isBoolean),
      "isArray and isBoolean cannot be true at the same time",
    )
    assert(
      values.forall(choices => choices.contains(default)),
      s"default must be one of values when values is defined. Got default: $default, values: $values",
    )
    assert(
      values.isEmpty || (!isArray && !isBoolean),
      "values cannot be combined with isArray/isBoolean flags",
    )
  }
}

trait UserConfigurationOption[A]
    extends ConfigurationOption[A, UserConfiguration]

object UserConfigurationOption {

  def printOneLiner(
      key: String,
      tpe: String,
      displayDefault: String,
      title: String,
  ): String =
    f"$key%-44s $tpe%-30s $displayDefault%-15s $title"
}

/** JSON plus the extra inputs needed while reading a user configuration. */
final class ConfigContext private (
    val json: JsonObject,
    properties: Properties,
    val clientConfiguration: ClientConfiguration,
    val featureFlags: FeatureFlagProvider,
    errorBuffer: ListBuffer[String],
    propertyPrefix: String,
) {
  def this(
      json: JsonObject,
      properties: Properties,
      clientConfiguration: ClientConfiguration,
      featureFlags: FeatureFlagProvider,
  ) = this(
    json,
    properties,
    clientConfiguration,
    featureFlags,
    ListBuffer.empty[String],
    "",
  )

  def nested(json: JsonObject, key: String): ConfigContext =
    new ConfigContext(
      json,
      properties,
      clientConfiguration,
      featureFlags,
      errorBuffer,
      s"$propertyPrefix$key.",
    )

  def addError(error: String): Unit =
    errorBuffer += error

  def errors: List[String] = errorBuffer.toList

  def get[A](
      key: String,
      currentObject: JsonObject,
      parse: JsonElement => Option[A],
  ): Option[A] = {
    def lookup[T](fn: String => T): Option[T] =
      Option(fn(key)).orElse(Option(fn(StringCase.kebabToCamel(key))))
    for {
      jsonValue <- lookup(name =>
        properties.getProperty(s"metals.$propertyPrefix$name")
      )
        .filterNot(_.isEmpty())
        .map(prop => new JsonPrimitive(prop))
        .orElse(lookup(currentObject.get))
      value <- parse(jsonValue)
    } yield value
  }

  def getString(key: String): Option[String] =
    getString(key, json)

  def getString(key: String, currentObject: JsonObject): Option[String] =
    get(
      key,
      currentObject,
      { value =>
        Try(value.getAsString)
          .fold(
            _ => {
              addError(
                s"json error: key '$key' should have value of type string but obtained $value"
              )
              None
            },
            Some(_),
          )
          .filter(_.nonEmpty)
      },
    )

  def getBoolean(key: String): Option[Boolean] =
    getBoolean(key, json)

  def getBoolean(key: String, currentObject: JsonObject): Option[Boolean] =
    get(
      key,
      currentObject,
      { value =>
        Try(value.getAsBoolean())
          .fold(
            _ => {
              addError(
                s"json error: key '$key' should have value of type boolean but obtained $value"
              )
              None
            },
            Some(_),
          )
      },
    )

  def getInt(key: String): Option[Int] =
    getInt(key, json)

  def getInt(key: String, currentObject: JsonObject): Option[Int] =
    getString(key, currentObject).flatMap { value =>
      Try(value.toInt) match {
        case Failure(_) =>
          addError(s"Not a number: '$value'")
          None
        case Success(parsed) =>
          Some(parsed)
      }
    }

  def getStringList(key: String): Option[List[String]] =
    get(
      key,
      json,
      { elem =>
        if (elem.isJsonArray()) {
          val parsed = List.newBuilder[String]
          for (value <- elem.getAsJsonArray().asScala) {
            Try(value.getAsJsonPrimitive().getAsString()) match {
              case Failure(_) =>
                addError(
                  s"json error: values in '$key' should have value of type string but obtained $value"
                )
              case Success(item) =>
                parsed += item
            }
          }
          Some(parsed.result())
        } else {
          addError(
            s"json error: key '$key' should have value of type array but obtained $elem"
          )
          None
        }
      },
    )

  def getStringMap(key: String): Option[Map[String, String]] =
    get(
      key,
      json,
      { value =>
        Try {
          value.getAsJsonObject
            .entrySet()
            .asScala
            .collect {
              case entry
                  if entry.getValue.isJsonPrimitive &&
                    entry.getValue.getAsJsonPrimitive.isString =>
                entry.getKey -> entry.getValue.getAsJsonPrimitive.getAsString
            }
            .toMap
        }.fold(
          _ => {
            addError(
              s"json error: key '$key' should have be object with string values but obtained $value"
            )
            None
          },
          entries => Some(entries).filter(_.nonEmpty),
        )
      },
    )

  def getStringListMap(key: String): Option[Map[String, List[String]]] =
    get(
      key,
      json,
      { value =>
        Try {
          value.getAsJsonObject
            .entrySet()
            .asScala
            .map { entry =>
              val values = entry.getValue
              if (!values.isJsonArray) {
                throw new IllegalArgumentException(
                  s"Expected array value for '${entry.getKey}'"
                )
              }
              val strings = values.getAsJsonArray.asScala.map { elem =>
                if (
                  !elem.isJsonPrimitive || !elem.getAsJsonPrimitive.isString
                ) {
                  throw new IllegalArgumentException(
                    s"Expected string value in array for '${entry.getKey}'"
                  )
                }
                elem.getAsString
              }.toList
              entry.getKey -> strings
            }
            .toMap
        }.fold(
          _ => {
            addError(
              s"json error: key '$key' should have be object with array string values but obtained $value"
            )
            None
          },
          entries => Some(entries).filter(_.nonEmpty),
        )
      },
    )

  def getObject(key: String): Option[JsonObject] =
    get(
      key,
      json,
      { value =>
        Try(value.getAsJsonObject())
          .fold(
            _ => {
              addError(
                s"json error: key '$key' should have value of type object but obtained $value"
              )
              None
            },
            Some(_),
          )
      },
    )
}
