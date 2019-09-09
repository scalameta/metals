package scala.meta.internal.metals
import com.google.gson.{Gson, JsonElement, JsonObject}

import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Success, Try}

sealed trait JsonParser {
  implicit class Parsable(data: Any) {
    def as[A: ClassTag]: Try[A] = {
      data match {
        case json: JsonElement =>
          val targetType = classTag[A].runtimeClass.asInstanceOf[Class[A]]
          Try(JsonParser.gson.fromJson(json, targetType))
        case value: A =>
          Success(value)
        case _ =>
          Failure(new IllegalArgumentException(s"Cannot convert [$data]"))
      }
    }
  }

  implicit class Serializable(data: Any) {
    def toJson: JsonElement = {
      JsonParser.gson.toJsonTree(data)
    }

    def toJsonObject: JsonObject = {
      data.toJson.getAsJsonObject
    }
  }
}

object JsonParser extends JsonParser {
  private val gson = new Gson()
}
