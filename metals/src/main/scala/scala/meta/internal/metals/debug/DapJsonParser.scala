package scala.meta.internal.metals.debug

import java.lang.reflect.Type

import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Try

import ch.epfl.scala.debugadapter.testing.SingleTestResult.Failed
import ch.epfl.scala.debugadapter.testing.SingleTestResult.Passed
import ch.epfl.scala.debugadapter.testing.SingleTestResult.Skipped
import ch.epfl.scala.debugadapter.testing.SingleTestSummary
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import org.eclipse.lsp4j.jsonrpc.debug.adapters.DebugEnumTypeAdapter

object DapJsonParser {
  private val gson: Gson = new GsonBuilder()
    .registerTypeAdapterFactory(
      // serialize Java enum to lower case
      new DebugEnumTypeAdapter.Factory
    )
    .registerTypeAdapter(
      classOf[SingleTestSummary],
      new SingleTestSummaryTypeAdapter,
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

  class SingleTestSummaryTypeAdapter
      extends JsonSerializer[SingleTestSummary]
      with JsonDeserializer[SingleTestSummary] {
    override def serialize(
        src: SingleTestSummary,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = {
      src match {
        case pass: Passed =>
          pass.toJsonObject
        case skip: Skipped =>
          skip.toJsonObject
        case fail: Failed =>
          fail.toJsonObject
      }
    }

    override def deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SingleTestSummary = {
      val jsonObject = json.getAsJsonObject
      jsonObject.get("kind").getAsString match {
        case "passed" =>
          jsonObject.as[Passed].get
        case "skipped" =>
          jsonObject.as[Skipped].get
        case "failed" =>
          jsonObject.as[Failed].get
      }
    }
  }

}
