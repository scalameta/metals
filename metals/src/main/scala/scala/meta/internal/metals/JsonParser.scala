package scala.meta.internal.metals

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Try

object JsonParser {
  private val gson = new Gson()

  implicit class Serialized(string: String) {
    def parseJson: JsonElement = {
      new JsonParser().parse(string)
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

  implicit class Deserializable(json: JsonElement) {
    def as[A: ClassTag]: Try[A] = {
      val targetType = classTag[A].runtimeClass.asInstanceOf[Class[A]]
      Try(JsonParser.gson.fromJson(json, targetType))
    }
  }
}
