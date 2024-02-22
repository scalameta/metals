package scala.meta.internal.metals.debug

import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Try

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.eclipse.lsp4j.jsonrpc.debug.adapters.DebugEnumTypeAdapter

object DapJsonParser {
  private val gson: Gson = new GsonBuilder()
    .registerTypeAdapterFactory(
      // serialize Java enum to lower case
      new DebugEnumTypeAdapter.Factory
    )
    .create

  implicit class XtensionSerializedJson(string: String) {
    def parseJson: JsonElement = {
      com.google.gson.JsonParser.parseString(string)
    }
  }

  implicit class XtensionSerializableToJson(data: Any) {
    def toJson: JsonElement = {
      gson.toJsonTree(data)
    }

    def toJsonObject: JsonObject = {
      data.toJson.getAsJsonObject
    }
  }

  implicit class XtensionSerializedAsJson(json: JsonElement) {
    def as[A: ClassTag]: Try[A] = {
      val targetType = classTag[A].runtimeClass.asInstanceOf[Class[A]]
      Try(gson.fromJson(json, targetType))
    }
  }

}
