package scala.meta.internal.metals

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Try

sealed trait JsonParser {
  implicit class Parsable(json: JsonElement) {
    def as[A: ClassTag]: Try[A] = {
      val targetType = classTag[A].runtimeClass.asInstanceOf[Class[A]]
      Try(JsonParser.gson.fromJson(json, targetType))
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
