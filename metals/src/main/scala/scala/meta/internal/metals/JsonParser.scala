package scala.meta.internal.metals

import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Try

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

object JsonParser {
  private val gson = new Gson()

  implicit class XtensionSerializedJson(string: String) {
    def parseJson: JsonElement = {
      com.google.gson.JsonParser.parseString(string)
    }
  }

  implicit class XtensionSerializableToJson(data: Any) {
    def toJson: JsonElement = {
      JsonParser.gson.toJsonTree(data)
    }

    def toJsonObject: JsonObject = {
      data.toJson.getAsJsonObject
    }
  }

  implicit class XtensionSerializedAsJson(json: JsonElement) {
    def as[A: ClassTag]: Try[A] = {
      val targetType = classTag[A].runtimeClass.asInstanceOf[Class[A]]
      Try(JsonParser.gson.fromJson(json, targetType))
    }
  }

  implicit class XtensionSerializedAsOption(json: JsonObject) {
    def getStringOption(key: String): Option[String] = {
      Try(json.get(key).getAsString()).toOption
    }

    def getBooleanOption(key: String): Option[Boolean] = {
      Try(json.get(key).getAsBoolean()).toOption
    }

    def getIntOption(key: String): Option[Int] = {
      Try(json.get(key).getAsInt()).toOption
    }

    def getObjectOption(key: String): Option[JsonObject] = {
      Try(json.get(key).toJsonObject).toOption
    }
  }

  // NOTE(alekseiAlefirov): one cannot do type parameterized extractor, unfortunately (https://github.com/scala/bug/issues/884)
  // so instantiating this class is a workaround
  class Of[A: ClassTag] {
    object Jsonized {
      def unapply(json: JsonElement): Option[A] = {
        json.as[A].toOption
      }
    }
  }

}
