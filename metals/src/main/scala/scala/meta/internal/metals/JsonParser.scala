package scala.meta.internal.metals

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Try

object JsonParser {
  private val gson = new Gson()

  implicit class XtensionSerializedJson(string: String) {
    def parseJson: JsonElement = {
      new com.google.gson.JsonParser().parse(string)
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
