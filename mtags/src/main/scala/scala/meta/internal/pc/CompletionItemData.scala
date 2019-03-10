package scala.meta.internal.pc

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

case class CompletionItemData(symbol: String, target: String) {
  def toJson: JsonElement = {
    val obj = new JsonObject()
    obj.add("symbol", new JsonPrimitive(symbol))
    obj.add("target", new JsonPrimitive(target))
    obj
  }
}
