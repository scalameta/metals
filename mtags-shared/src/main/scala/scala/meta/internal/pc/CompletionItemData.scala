package scala.meta.internal.pc

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

case class CompletionItemData(
    symbol: String,
    target: String,
    // The kind of the completion item, for example `override def`
    kind: java.lang.Integer = null,
    additionalSymbols: java.util.List[String] = null
) {
  def toJson: JsonElement = {
    val obj = new JsonObject()
    obj.add("symbol", new JsonPrimitive(symbol))
    obj.add("target", new JsonPrimitive(target))
    if (kind != null) {
      obj.add("kind", new JsonPrimitive(kind))
    }
    if (additionalSymbols != null) {
      val arr = new JsonArray()
      additionalSymbols.forEach(str => arr.add(new JsonPrimitive(str)))
      obj.add("additionalSymbols", arr)
    }
    obj
  }
}

object CompletionItemData {
  def empty: CompletionItemData = CompletionItemData("", "")
  val None: java.lang.Integer = 0
  // This is an `override def` completion item.
  val OverrideKind: java.lang.Integer = 1
  // This is a completion implementing all abstract members
  val ImplementAllKind: java.lang.Integer = 2
}
